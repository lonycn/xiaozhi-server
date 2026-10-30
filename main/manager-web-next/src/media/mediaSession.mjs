import { createLiveKitTransport } from './livekitTransport.mjs';
export const MEDIA_TERMINAL = new Set(['ended','rejected','canceled','expired','failed']);
export const TRANSFER_PENDING = new Set(['invited','preparing','ready','committing']);
export function mediaErrorKey(error) {
  if (error.name === 'NotAllowedError' || error.name === 'SecurityError') return 'media.permissionDenied';
  if (error.name === 'NotFoundError') return 'media.deviceMissing';
  if (error.status === 401 || error.status === 403) return 'media.denied';
  if (error.status === 404) return 'media.unavailable';
  if (['ROOM_PREPARING','MEDIA_ROOM_PREPARING'].includes(error.code)) return 'media.roomPreparing';
  if (error.status === 409) return 'media.changed';
  if (error.code === 'TIMEOUT') return 'media.timeout';
  return 'media.failed';
}

/** Owns ephemeral tokens, generation fences and both rooms. No persisted credentials. */
export function createMediaSession({ client, userId, changed = () => {}, onTrack = () => {}, transportFactory = createLiveKitTransport }) {
  let session = null, version = 0, controller = new AbortController(), consent = null, joined = false, disposed = false;
  const rooms = new Map(), states = new Map();
  let microphone = false, camera = false, error = '', busy = false;
  const me = (generation = session?.current_generation) => session?.participants?.find(member => String(member.user_id) === String(userId()) && member.room_generation === generation);
  const snapshot = () => ({ session, joined, microphone, camera, error, busy, localState: states.get(session?.current_generation)?.state || 'disconnected', playbackBlocked: !!states.get(session?.current_generation)?.playbackBlocked, hasVideo: !!states.get(session?.current_generation)?.hasVideo });
  const emit = () => changed(snapshot());
  const fresh = epoch => !disposed && epoch === version && !controller.signal.aborted;
  function closeGeneration(generation) { const entry = rooms.get(generation); rooms.delete(generation); states.delete(generation); if (entry) return entry.close().catch(() => {}); }
  function stop() { ++version; controller.abort(); controller = new AbortController(); joined = false; consent = null; microphone = false; camera = false; for (const generation of rooms.keys()) closeGeneration(generation); busy = false; emit(); }
  async function apply(next, epoch = version) {
    if (!fresh(epoch)) return;
    if (!next || (session && next.id !== session.id)) stop();
    session = next;
    if (!session || MEDIA_TERMINAL.has(session.status) || !session.participants?.some(member => String(member.user_id) === String(userId()))) { stop(); emit(); return; }
    // A revoked membership/consent is authoritative even if a JWT has time remaining.
    const own = me();
    if (!own && !me(session.preparing_generation)) { stop(); error = 'media.transferred'; emit(); return; }
    if (rooms.has(session.current_generation) && (!own || !own.consent?.microphone || ['removed','revoked','left'].includes(own.status))) { stop(); error = 'media.denied'; emit(); return; }
    if (consent && own?.consent) {
      if (!own.consent.camera && camera) { camera = false; for (const room of rooms.values()) await room.camera(false); }
      consent = { ...consent, camera: !!own.consent.camera, allow_snapshots: !!own.consent.allow_snapshots };
    }
    const current = rooms.get(session.current_generation);
    if (current) {
      // Stop old publications and output before allowing new output. This also covers
      // late completion of a server-side handoff after a locally interrupted poll.
      for (const generation of [...rooms.keys()]) if (generation !== session.current_generation && generation !== session.preparing_generation) await closeGeneration(generation);
      if (!fresh(epoch)) return;
      current.setForeground(true);
    }
    const transfer = session.pending_transfer;
    for (const generation of [...rooms.keys()]) {
      if (generation !== session.current_generation && (!TRANSFER_PENDING.has(transfer?.status) || generation !== transfer.room_generation)) await closeGeneration(generation);
    }
    emit();
  }
  async function connect(generation) {
    if (!session || !joined || !consent || rooms.has(generation)) return;
    const epoch = version, id = session.id;
    const token = await client.token(id, { expected_epoch: session.control_epoch, room_generation: generation }, controller.signal);
    if (!fresh(epoch) || session?.id !== id) return;
    if (token.session_id !== id || token.room_generation !== generation || token.control_epoch !== session.control_epoch || !token.server_url || !token.participant_token || !token.publish_sources?.includes('microphone')) throw new Error('INVALID_MEDIA_TOKEN');
    const room = await transportFactory({ onChange: state => { if (fresh(epoch) && rooms.get(generation) === room) { states.set(generation, { ...states.get(generation), ...state }); emit(); } }, onTrack: (element, track) => { if (fresh(epoch)) onTrack(element, track, generation); else element.remove(); } });
    if (!fresh(epoch)) { await room.close(); return; }
    rooms.set(generation, room); room.setForeground(generation === session.current_generation);
    try {
      await room.connect(token.server_url, token.participant_token, { microphone: consent.microphone && microphone, camera: !!me(generation)?.consent?.camera && camera && token.publish_sources.includes('camera') });
      if (!fresh(epoch)) { await room.close(); return; }
    } catch (failure) { await closeGeneration(generation); throw failure; }
  }
  async function operation(work) {
    if (busy || disposed) return; busy = true; error = ''; emit(); const epoch = version;
    try { await work(epoch); }
    catch (failure) { if (fresh(epoch) && failure.name !== 'AbortError') { error = mediaErrorKey(failure); if ([401,403].includes(failure.status)) stop(); } }
    finally { if (fresh(epoch)) { busy = false; emit(); } }
  }
  async function tick() {
    if (!session || busy || disposed) return;
    await operation(async epoch => {
      await apply(await client.get(session.id, controller.signal), epoch);
      if (!fresh(epoch) || !session || MEDIA_TERMINAL.has(session.status) || session.status === 'invited' || !joined) return;
      const transfer = session.pending_transfer, generation = session.current_generation;
      // Target agents must only ever join the new generation. Source agents never get it.
      if (me(generation)?.consent?.microphone) await connect(generation);
      if (!fresh(epoch)) return;
      if (transfer && ['preparing','ready'].includes(transfer.status) && transfer.customer_approved && transfer.target_accepted &&
          me(transfer.room_generation)?.consent?.microphone && session.preparing_generation === transfer.room_generation) await connect(transfer.room_generation);
      for (const [roomGeneration, room] of [...rooms]) {
        const proof = await room.evidence();
        if (!fresh(epoch)) return;
        if (!proof.participant_sid || !proof.peer_sid || !proof.transport_ready) continue;
        // A subscribed track alone never marks the call active. The server independently
        // verifies SIDs and ACTIVE participants, and decides its persisted status.
        await client.ready(session.id, { expected_epoch: session.control_epoch, room_generation: roomGeneration, ...proof }, controller.signal);
      }
    });
  }
  return {
    snapshot, me,
    async load(value) { await operation(async epoch => { await apply(value, epoch); }); },
    async refresh() { await tick(); },
    async create(requestId, choice, clientSessionId) { if (session && MEDIA_TERMINAL.has(session.status)) { stop(); session = null; } await operation(async epoch => { const next = await client.create({ request_id: requestId, client_session_id: clientSessionId, mode: choice.camera ? 'video' : 'audio', consent: choice }, controller.signal); if (!fresh(epoch)) return; await apply(next, epoch); consent = { ...choice }; microphone = !!choice.microphone; camera = !!choice.camera; joined = true; emit(); }); },
    async accept(choice) { await operation(async epoch => { const next = await client.accept(session.id, { expected_epoch: session.control_epoch, consent: choice }, controller.signal); if (!fresh(epoch)) return; consent = { ...choice }; microphone = !!choice.microphone; camera = !!choice.camera; joined = true; await apply(next, epoch); }); await tick(); },
    async reject() { await operation(async epoch => { await apply(await client.reject(session.id, { expected_epoch: session.control_epoch }, controller.signal), epoch); }); },
    async reconnect(choice) { stop(); await operation(async epoch => { const next = await client.get(session.id, controller.signal); if (!fresh(epoch)) return; const own = next.participants?.find(member => String(member.user_id) === String(userId()) && member.room_generation === next.current_generation); if (!own?.consent?.microphone) throw Object.assign(new Error('DENIED'), { status:403 }); consent = { microphone: true, camera: !!choice.camera && !!own.consent.camera, allow_snapshots: !!own.consent.allow_snapshots }; microphone = true; camera = consent.camera; joined = true; await apply(next, epoch); }); await tick(); },
    async end() {
      if (!session) return; const id = session.id, expected_epoch = session.control_epoch;
      // End local capture immediately, even when the API is unavailable.
      stop(); await operation(async epoch => { await apply(await client.end(id, { expected_epoch, reason:'user_hangup' }, controller.signal), epoch); });
    },
    async toggle(source) { await operation(async () => { const value = source === 'camera' ? !camera : !microphone; if (source === 'camera' && value && !consent?.camera) throw Object.assign(new Error('CONSENT_REQUIRED'), { status:403 }); for (const [generation, room] of rooms) await room[source](source === 'camera' ? value && !!me(generation)?.consent?.camera : value); if (source === 'camera') camera = value; else microphone = value; }); },
    async resumeAudio() { await operation(async () => { for (const [generation, room] of rooms) if (generation === session?.current_generation) await room.resumeAudio(); const state = states.get(session?.current_generation); if (state) state.playbackBlocked = false; }); },
    async transfer(target, clientTransferId) { await operation(async epoch => { await client.transfer(session.id, { expected_epoch: session.control_epoch, client_transfer_id:clientTransferId, target_agent_user_id:target }, controller.signal); if (fresh(epoch)) await apply(await client.get(session.id, controller.signal), epoch); }); },
    async transferAction(action, choice) { await operation(async epoch => { const transfer = session.pending_transfer; if (!transfer) return; await client.transferAction(transfer.id, action, { expected_epoch: session.control_epoch, ...(['approve','accept'].includes(action) ? { consent:choice } : {}) }, controller.signal); if (!fresh(epoch)) return; if (['approve','accept'].includes(action)) { consent = { ...choice }; microphone = !!choice.microphone; camera = !!choice.camera; joined = true; if (!camera) for (const room of rooms.values()) await room.camera(false); } await apply(await client.get(session.id, controller.signal), epoch); }); await tick(); },
    videoTrack() { return rooms.get(session?.current_generation)?.videoTrack(); },
    stop,
    dispose() { disposed = true; stop(); },
  };
}
