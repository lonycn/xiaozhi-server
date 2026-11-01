/** SDK boundary, lazily loaded so normal text conversations do not download WebRTC. */
export async function createLiveKitTransport(options) {
  const sdk = await import('livekit-client');
  sdk.setLogLevel(sdk.LogLevel.silent);
  return createRoomTransport(sdk, options);
}

export function createRoomTransport(sdk, { onChange = () => {}, onTrack = () => {}, onLocalTrack = () => {}, now = () => Date.now() } = {}) {
  const room = new sdk.Room({ adaptiveStream: true, dynacast: true });
  const local = new Map();
  const tracks = new Set(), attachments = new Map(), samples = new Map();
  let closed = false, foreground = false, connected = false, mediaStalled = false;
  const flow = new Map(), sourceVersions = new Map(), stopped = new WeakSet(); let captureEpoch = 0;
  function stopTrack(track) { if (!stopped.has(track)) { stopped.add(track); track.stop(); } }
  const update = () => onChange({ connected: connected && room.state === 'connected', state: room.state, hasVideo: foreground && [...tracks].some(track => track.kind === 'video' && !track.isMuted), hasLocalVideo: foreground && local.has('video') && !local.get('video').isMuted, mediaStalled });
  function render(track, isLocal = false) {
    if (!foreground || attachments.has(track)) return;
    const element = track.attach(); element.autoplay = true; element.playsInline = true;
    if (track.kind === 'audio') element.hidden = true;
    if (isLocal) element.muted = true;
    attachments.set(track, element); (isLocal ? onLocalTrack : onTrack)(element, track);
    element.play?.().catch(() => { if (!isLocal) onChange({ playbackBlocked: true }); });
  }
  function detach(track) {
    const owned = attachments.get(track);
    for (const element of track.detach?.() || []) element.remove();
    // LiveKit may detach first on unsubscribe, leaving its element list empty.
    owned?.remove(); attachments.delete(track); samples.delete(track.sid); flow.delete(track.sid);
  }
  room.on(sdk.RoomEvent.TrackSubscribed, track => { if (!closed) { tracks.add(track); render(track); update(); } });
  room.on(sdk.RoomEvent.TrackUnsubscribed, track => { tracks.delete(track); detach(track); update(); });
  room.on(sdk.RoomEvent.ConnectionStateChanged, () => { if (room.state !== 'connected') { mediaStalled = false; flow.clear(); samples.clear(); } update(); });
  room.on(sdk.RoomEvent.Disconnected, () => { ++captureEpoch; connected = false; mediaStalled = false; flow.clear(); samples.clear(); for (const track of local.values()) { detach(track); stopTrack(track); } local.clear(); for (const track of tracks) detach(track); update(); });
  if (sdk.RoomEvent.LocalTrackUnpublished) room.on(sdk.RoomEvent.LocalTrackUnpublished, publication => {
    for (const [kind, track] of local) if (publication.track === track) { detach(track); stopTrack(track); local.delete(kind); }
    update();
  });
  async function source(kind, enabled) {
    const version = (sourceVersions.get(kind) || 0) + 1; sourceVersions.set(kind, version);
    const epoch = captureEpoch, old = local.get(kind);
    if (!enabled) { if (old) { detach(old); stopTrack(old); local.delete(kind); update(); await room.localParticipant.unpublishTrack(old); } return; }
    if (old || closed) return;
    const captured = await sdk.createLocalTracks({ audio: kind === 'audio', video: kind === 'video' });
    for (const track of captured) {
      if (closed || epoch !== captureEpoch || sourceVersions.get(kind) !== version) { stopTrack(track); continue; }
      local.set(kind, track);
      try { await room.localParticipant.publishTrack(track); }
      catch (error) { detach(track); stopTrack(track); if (local.get(kind) === track) local.delete(kind); throw error; }
      if (closed || epoch !== captureEpoch || sourceVersions.get(kind) !== version) { detach(track); stopTrack(track); if (local.get(kind) === track) local.delete(kind); await room.localParticipant.unpublishTrack(track); }
      else if (kind === 'video') render(track, true);
    }
    update();
  }
  return {
    async connect(url, token, consent) {
      if (closed) throw new DOMException('Aborted', 'AbortError');
      await room.connect(url, token, { autoSubscribe: true });
      if (closed) { await room.disconnect(); throw new DOMException('Aborted', 'AbortError'); }
      connected = true;
      // Each source is explicitly consented. No camera is requested for an audio-only choice.
      if (consent.microphone) await source('audio', true);
      if (closed) { await room.disconnect(); throw new DOMException('Aborted', 'AbortError'); }
      if (consent.camera) await source('video', true);
      if (closed) { await room.disconnect(); throw new DOMException('Aborted', 'AbortError'); }
      update();
    },
    setForeground(value) {
      foreground = value;
      for (const track of tracks) { if (value) render(track); else detach(track); }
      const preview = local.get('video'); if (preview) { if (value) render(preview, true); else detach(preview); } update();
    },
    async microphone(value) { await source('audio', value); },
    async camera(value) { await source('video', value); },
    async resumeAudio() { if (!closed) await room.startAudio(); },
    async evidence() {
      const participant = [...room.remoteParticipants.values()][0];
      const proof = { participant_sid: room.localParticipant.sid || '', peer_sid: participant?.sid || '', transport_ready: connected && room.state === 'connected', receiving_audio: false, receiving_video: false };
      let stalled = false;
      for (const track of tracks) {
        const publication = participant && [...participant.trackPublications.values()].find(pub => pub.track === track);
        // A remote user muting, an explicit subscription pause, and adaptive video
        // suspended in a hidden/preparing view are not network receive failures.
        const expected = publication && publication.isSubscribed !== false && publication.isEnabled !== false &&
          !track.isMuted && track.streamState !== 'paused' && track.mediaStreamTrack?.readyState !== 'ended' &&
          !(track.kind === 'video' && (!foreground || globalThis.document?.hidden));
        if (!expected || !proof.transport_ready) { samples.delete(track.sid); flow.delete(track.sid); continue; }
        const report = await track.getRTCStatsReport(); let bytes = 0;
        report?.forEach(stat => { if (stat.type === 'inbound-rtp' && !stat.isRemote) bytes += Number(stat.bytesReceived || 0); });
        const previous = samples.get(track.sid), observedAt = now();
        // Require newly received packets, not simply a subscribed track or an old total.
        if (previous !== undefined && bytes > previous) proof[track.kind === 'audio' ? 'receiving_audio' : 'receiving_video'] = true;
        if (previous === undefined || bytes !== previous) flow.set(track.sid, observedAt); // Includes SSRC/counter resets.
        samples.set(track.sid, bytes);
        if (observedAt - flow.get(track.sid) >= 6000) stalled = true;
      }
      if (mediaStalled !== stalled) { mediaStalled = stalled; update(); }
      return proof;
    },
    videoTrack() { return [...tracks].find(track => track.kind === 'video' && !track.isMuted); },
    async close() {
      if (closed) return; closed = true; ++captureEpoch; connected = false; mediaStalled = false; flow.clear(); samples.clear();
      for (const track of tracks) detach(track); tracks.clear();
      // Stop hardware synchronously, including while an async publication is in progress.
      for (const track of local.values()) { detach(track); stopTrack(track); } local.clear();
      for (const publication of room.localParticipant.trackPublications.values()) publication.track && stopTrack(publication.track);
      await room.disconnect(true); room.removeAllListeners();
    },
  };
}
