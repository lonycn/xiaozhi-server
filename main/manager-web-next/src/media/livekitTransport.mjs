/** SDK boundary, lazily loaded so normal text conversations do not download WebRTC. */
export async function createLiveKitTransport(options) {
  const sdk = await import('livekit-client');
  sdk.setLogLevel(sdk.LogLevel.silent);
  return createRoomTransport(sdk, options);
}

export function createRoomTransport(sdk, { onChange = () => {}, onTrack = () => {} } = {}) {
  const room = new sdk.Room({ adaptiveStream: true, dynacast: true });
  const local = new Map();
  const tracks = new Set(), attachments = new Map(), samples = new Map();
  let closed = false, foreground = false, connected = false;
  const update = () => onChange({ connected: connected && room.state === 'connected', state: room.state, hasVideo: foreground && [...tracks].some(track => track.kind === 'video' && !track.isMuted) });
  function render(track) {
    if (!foreground || attachments.has(track)) return;
    const element = track.attach(); element.autoplay = true; element.playsInline = true;
    if (track.kind === 'audio') element.hidden = true;
    attachments.set(track, element); onTrack(element, track);
    element.play?.().catch(() => onChange({ playbackBlocked: true }));
  }
  function detach(track) {
    const owned = attachments.get(track);
    for (const element of track.detach()) element.remove();
    // LiveKit may detach first on unsubscribe, leaving its element list empty.
    owned?.remove(); attachments.delete(track); samples.delete(track.sid);
  }
  room.on(sdk.RoomEvent.TrackSubscribed, track => { if (!closed) { tracks.add(track); render(track); update(); } });
  room.on(sdk.RoomEvent.TrackUnsubscribed, track => { tracks.delete(track); detach(track); update(); });
  room.on(sdk.RoomEvent.ConnectionStateChanged, () => update());
  room.on(sdk.RoomEvent.Disconnected, () => { connected = false; for (const track of local.values()) track.stop(); local.clear(); for (const track of tracks) detach(track); update(); });
  async function source(kind, enabled) {
    const old = local.get(kind);
    if (!enabled) { if (old) { old.stop(); local.delete(kind); await room.localParticipant.unpublishTrack(old); } return; }
    if (old || closed) return;
    const captured = await sdk.createLocalTracks({ audio: kind === 'audio', video: kind === 'video' });
    for (const track of captured) {
      if (closed) { track.stop(); continue; }
      local.set(kind, track);
      try { await room.localParticipant.publishTrack(track); }
      catch (error) { track.stop(); local.delete(kind); throw error; }
      if (closed) { track.stop(); local.delete(kind); }
    }
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
      for (const track of tracks) { if (value) render(track); else detach(track); } update();
    },
    async microphone(value) { await source('audio', value); },
    async camera(value) { await source('video', value); },
    async resumeAudio() { if (!closed) await room.startAudio(); },
    async evidence() {
      const participant = [...room.remoteParticipants.values()][0];
      const proof = { participant_sid: room.localParticipant.sid || '', peer_sid: participant?.sid || '', transport_ready: connected && room.state === 'connected', receiving_audio: false, receiving_video: false };
      for (const track of tracks) {
        if (!participant || ![...participant.trackPublications.values()].some(pub => pub.track === track) || track.isMuted) continue;
        const report = await track.getRTCStatsReport();
        report?.forEach(stat => {
          if (stat.type !== 'inbound-rtp' || stat.isRemote) return;
          const bytes = Number(stat.bytesReceived || 0), old = samples.get(track.sid);
          samples.set(track.sid, bytes);
          // Require newly received packets, not simply a subscribed track or an old total.
          if (old !== undefined && bytes > old) proof[track.kind === 'audio' ? 'receiving_audio' : 'receiving_video'] = true;
        });
      }
      return proof;
    },
    videoTrack() { return [...tracks].find(track => track.kind === 'video' && !track.isMuted); },
    async close() {
      if (closed) return; closed = true; connected = false;
      for (const track of tracks) detach(track); tracks.clear();
      // Stop hardware synchronously, including while an async publication is in progress.
      for (const track of local.values()) track.stop(); local.clear();
      for (const publication of room.localParticipant.trackPublications.values()) publication.track?.stop();
      await room.disconnect(true); room.removeAllListeners();
    },
  };
}
