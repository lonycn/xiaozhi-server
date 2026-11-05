import { reactive } from 'vue';
import { cursorInteger, supportRoles, supportAccepting } from '../utils/support.mjs';
import { messagePreview } from './activity.mjs';
import { createStaffAudioAlerts } from './audioAlerts.mjs';

/** One account-scoped session belongs to the shell, independently of the current desk route. */
export function createSupportSession({ api, conversations, media, notify = () => {}, sound, storage, locks, isVisible = () => true, now = Date.now, pollMs = 4000, heartbeatMs = 15000, callPollMs = 2500 }) {
  const state = reactive({
    profile: null, loading: false, presenceBusy: false, error: '',
    unread: {}, newRequests: {}, queueCounts: {}, requests: [], recent: [], activity: {}, calls: [],
    soundEnabled: true, soundReady: false, soundAvailable: !!sound?.available,
  });
  let generation = 0, key = '', controller = null, pollTimer = null, heartbeatTimer = null, callTimer = null, readyTask = Promise.resolve(null);
  let presenceTail = Promise.resolve(), polling = false, view = null, soundKey = '';
  let queues = new Map(), assigned = new Map(), cursors = new Map();
  let progressKey = '', savedProgress = {}, progressReady = false, callsPolling = false, mediaUnavailable = false;
  let callKeys = new Set(), silencedCalls = new Set();
  const notices = new Map();
  const audio = createStaffAudioAlerts({ sound, locks, storage, userId: () => state.profile?.user_id, now });
  const staff = profile => profile?.enabled === true && supportRoles(profile).length > 0;
  const current = epoch => epoch === generation && !!key && !controller?.signal.aborted;
  function clearTimers() { clearInterval(pollTimer); clearInterval(heartbeatTimer); clearInterval(callTimer); pollTimer = null; heartbeatTimer = null; callTimer = null; }
  function closeNotice(id) { const close = notices.get(id); notices.delete(id); try { close?.(); } catch { /* Optional host. */ } }
  function closeNotices() { for (const id of notices.keys()) closeNotice(id); }
  function saveProgress() {
    if (!progressKey || !progressReady) return;
    const values = {};
    for (const request of state.requests.filter(row => row.status === 'active').slice(0, 200)) {
      if (!cursors.has(request.request_id)) continue;
      values[request.request_id] = { conversation_id: request.conversation_id, service_type: request.service_type || 'support_agent', after: cursors.get(request.request_id), count: state.unread[request.request_id]?.count || 0 };
    }
    try { storage?.setItem(progressKey, JSON.stringify(values)); } catch { /* Storage is optional. */ }
  }
  function syncRinging() { audio.ringing(state.soundEnabled && state.soundReady && state.calls.some(call => !silencedCalls.has(call.call_key) && Date.parse(call.expires_at) > now())); }
  function silenceCall(id) { silencedCalls.add(id); syncRinging(); }
  function stop() {
    saveProgress(); audio.stop();
    ++generation; key = ''; controller?.abort(); controller = null; clearTimers(); closeNotices();
    sound?.close?.(); view = null; polling = false; presenceTail = Promise.resolve();
    queues = new Map(); assigned = new Map(); cursors = new Map(); soundKey = ''; progressKey = ''; savedProgress = {}; progressReady = false;
    callKeys = new Set(); silencedCalls = new Set(); callsPolling = false; mediaUnavailable = false;
    state.profile = null; state.loading = false; state.presenceBusy = false; state.error = '';
    state.unread = {}; state.newRequests = {}; state.queueCounts = {}; state.requests = []; state.recent = []; state.activity = {}; state.calls = [];
    state.soundReady = false; state.soundEnabled = true;
  }
  function applyProfile(profile) {
    state.profile = staff(profile) ? profile : null;
    if (!state.profile) return;
    const nextSoundKey = 'staff-notification-sound:' + String(profile.user_id);
    if (nextSoundKey !== soundKey) {
      soundKey = nextSoundKey;
      progressKey = 'staff-message-progress:' + String(profile.user_id);
      try { savedProgress = JSON.parse(storage?.getItem(progressKey) || '{}') || {}; } catch { savedProgress = {}; }
    }
    syncSoundPreference();
  }
  function markRead(requestId) { delete state.unread[requestId]; delete state.newRequests[requestId]; closeNotice('request:' + requestId); saveProgress(); }
  function viewing(request) {
    return !!view && view.requestId === request.request_id && view.conversationId === request.conversation_id
      && view.serviceType === (request.service_type || 'support_agent') && isVisible() && view.isReading?.() === true;
  }
  function setView(owner, next) { view = next ? { ...next, owner } : null; if (next && next.isReading?.() && isVisible()) markRead(next.requestId); }
  function clearView(owner) { if (view?.owner === owner) view = null; }
  function reveal(requestId) { if (view?.requestId !== requestId || !view.openLatest) return false; void view.openLatest(); return true; }
  function alert(event) {
    event = { ...event, title: state.activity[event.request_id]?.title || '', preview: state.activity[event.request_id]?.preview, ...event };
    const id = event.kind === 'call' ? 'call:' + event.call_key : 'request:' + event.request_id;
    state.recent = [event, ...state.recent.filter(item => (item.call_key || item.request_id) !== (event.call_key || event.request_id))].slice(0, 30);
    closeNotice(id);
    try { const close = notify(event); if (typeof close === 'function') { notices.set(id, close); if (notices.size > 20) closeNotice(notices.keys().next().value); } } catch { /* Badges still show the event. */ }
    if (event.kind !== 'call' && state.soundEnabled) void audio.beep(event);
  }
  function enqueuePresence(operation) {
    const epoch = generation;
    const task = presenceTail.catch(() => {}).then(async () => {
      if (!current(epoch) || !staff(state.profile)) throw new DOMException('Aborted', 'AbortError');
      state.presenceBusy = true;
      try {
        const run = () => { if (!current(epoch)) throw new DOMException('Aborted', 'AbortError'); return operation(epoch, controller.signal); };
        // A second tab must read the latest preference after a user's write, never renew an older one.
        return await (locks?.request ? locks.request('staff-presence:' + state.profile.user_id, { signal: controller.signal }, run) : run());
      }
      finally { if (current(epoch)) state.presenceBusy = false; }
    });
    presenceTail = task;
    return task;
  }
  async function presenceError(error, epoch) {
    if (!current(epoch) || error.name === 'AbortError') return;
    if ([401, 403].includes(error.status)) {
      try { const profile = await api.me(controller.signal); if (!current(epoch)) return; if (!staff(profile)) { stop(); return; } applyProfile(profile); }
      catch (check) { if (current(epoch) && [401,403].includes(check.status)) { stop(); return; } }
    }
    if (current(epoch)) state.error = error.message;
  }
  function setAccepting(value, serviceType = state.profile?.role) {
    if (typeof value !== 'boolean') return Promise.reject(new TypeError('INVALID_ACCEPTING'));
    if (!supportRoles(state.profile).includes(serviceType)) return Promise.reject(new TypeError('INVALID_SERVICE_TYPE'));
    return enqueuePresence(async (epoch, signal) => {
      try {
        const profile = await api.presence(value, serviceType, signal);
        if (!current(epoch)) throw new DOMException('Aborted', 'AbortError');
        if (!staff(profile)) { stop(); throw new DOMException('Aborted', 'AbortError'); }
        applyProfile(profile); state.error = ''; return profile;
      } catch (error) {
        // Reconcile an ambiguous write instead of silently displaying the opposite state.
        if (current(epoch) && error.name !== 'AbortError') {
          try { const profile = await api.me(signal); if (current(epoch) && staff(profile)) applyProfile(profile); } catch { /* Keep the last confirmed state. */ }
          await presenceError(error, epoch);
        }
        throw error;
      }
    });
  }
  function heartbeat() {
    return enqueuePresence(async (epoch, signal) => {
      try {
        // Other tabs or administrators may have changed the preference.
        const profile = await api.me(signal);
        if (!current(epoch)) return;
        if (!staff(profile)) { stop(); return; }
        applyProfile(profile);
        for (const role of supportRoles(state.profile)) {
          if (!supportAccepting(state.profile, role)) continue;
          const renewed = await api.presence(true, role, signal);
          if (!current(epoch)) return;
          if (!staff(renewed)) { stop(); return; }
          applyProfile(renewed);
        }
        state.error = ''; return state.profile;
      } catch (error) { await presenceError(error, epoch); }
    }).catch(() => {});
  }
  async function listAll(fetchPage, epoch) {
    const items = []; let before = '0';
    while (current(epoch)) {
      const page = await fetchPage(before);
      if (!current(epoch)) return [];
      items.push(...page.items);
      if (!page.has_more) return items;
      if (cursorInteger(page.next_cursor) === 0n || (before !== '0' && cursorInteger(page.next_cursor) >= cursorInteger(before))) throw new Error('INVALID_SUPPORT_CURSOR');
      before = String(page.next_cursor);
    }
    return [];
  }
  function recordActivity(request, message, detail) {
    const id = request.request_id, previous = state.activity[id] || {};
    state.activity[id] = {
      ...previous, title: detail?.title || previous.title || '',
      at: Date.parse(message?.created_at || detail?.updated_at || request.requested_at || '') || now(),
      ...(message ? { preview: messagePreview(message.parts), seq: String(message.seq) } : {}),
    };
  }
  async function messagesFor(request, epoch) {
    const id = request.request_id;
    if (!cursors.has(id)) {
      const detail = await conversations.detail(request.conversation_id, controller.signal);
      if (!current(epoch)) return;
      const latest = cursorInteger(detail.last_seq), saved = savedProgress[id];
      recordActivity(request, null, detail);
      if (latest > 0n) {
        const tail = await conversations.messages(request.conversation_id, String(latest - 1n), controller.signal, 1);
        if (!current(epoch)) return;
        if (tail.items[0]) recordActivity(request, tail.items[0], detail);
      }
      const valid = saved && saved.conversation_id === request.conversation_id && saved.service_type === (request.service_type || 'support_agent')
        && typeof saved.after === 'string' && /^[0-9]+$/.test(saved.after) && BigInt(saved.after) <= latest;
      cursors.set(id, valid ? saved.after : String(latest));
      if (valid && Number.isSafeInteger(saved.count) && saved.count > 0 && !viewing(request)) {
        state.unread[id] = { count: Math.min(saved.count, 99999), service_type: request.service_type || 'support_agent', conversation_id: request.conversation_id };
      }
      if (!valid || BigInt(saved.after) === latest) return;
    }
    let after = cursors.get(id), more = true, incoming = 0, lastIncoming = null;
    while (more && current(epoch)) {
      const page = await conversations.messages(request.conversation_id, after, controller.signal, 100);
      if (!current(epoch)) return;
      const next = String(cursorInteger(page.next_cursor));
      if ((page.has_more || page.items.length) && cursorInteger(next) <= cursorInteger(after)) throw new Error('INVALID_SUPPORT_CURSOR');
      for (const message of page.items.filter(row => cursorInteger(row.seq) > cursorInteger(after))) {
        recordActivity(request, message);
        if (message.sender_type === 'user') { ++incoming; lastIncoming = message; }
      }
      if (cursorInteger(next) > cursorInteger(after)) cursors.set(id, next);
      after = next; more = page.has_more;
    }
    if (!incoming || !current(epoch)) return;
    if (viewing(request)) { markRead(id); return; }
    const count = (state.unread[id]?.count || 0) + incoming;
    state.unread[id] = { count, service_type: request.service_type || 'support_agent', conversation_id: request.conversation_id };
    alert({ kind: 'message', request_id: id, conversation_id: request.conversation_id, service_type: request.service_type || 'support_agent',
      count: incoming, unread_count: count, last_seq: after, preview: messagePreview(lastIncoming?.parts), at: now() });
  }
  async function poll() {
    if (polling || !key || !staff(state.profile)) return;
    polling = true; const epoch = generation, signal = controller.signal, roles = supportRoles(state.profile);
    for (const role of queues.keys()) if (!roles.includes(role)) { queues.delete(role); assigned.delete(role); delete state.queueCounts[role]; }
    try {
      const snapshots = await Promise.all(roles.map(async role => ({
        role,
        queue: await listAll(before => api.queue(before, role, signal), epoch),
        assigned: await listAll(before => api.assigned(before, role, signal), epoch),
      })));
      if (!current(epoch)) return;
      const active = [], all = [], valid = new Set();
      for (const snapshot of snapshots) {
        if (!supportRoles(state.profile).includes(snapshot.role)) continue;
        const role = snapshot.role, previousQueue = queues.get(role), previousAssigned = assigned.get(role);
        const queue = snapshot.queue.filter(row => row.status === 'queued' && (row.service_type || 'support_agent') === role);
        const own = snapshot.assigned.filter(row => row.status === 'active' && (row.service_type || 'support_agent') === role && String(row.assigned_agent_user_id) === String(state.profile.user_id));
        state.queueCounts[role] = queue.length;
        for (const request of [...queue, ...own]) { all.push(request); valid.add(request.request_id); }
        for (const request of queue) if (previousQueue && !previousQueue.has(request.request_id) && !previousAssigned?.has(request.request_id) && supportAccepting(state.profile, role)) {
          state.newRequests[request.request_id] = { service_type: role, kind: 'queue', conversation_id: request.conversation_id };
          alert({ ...state.newRequests[request.request_id], request_id: request.request_id, at: now() });
        }
        for (const request of own) {
          active.push(request);
          if (previousAssigned && !previousAssigned.has(request.request_id) && !viewing(request)) {
            state.newRequests[request.request_id] = { service_type: role, kind: 'assigned', conversation_id: request.conversation_id };
            alert({ ...state.newRequests[request.request_id], request_id: request.request_id, at: now() });
          }
        }
        queues.set(role, new Set(queue.map(row => row.request_id))); assigned.set(role, new Set(own.map(row => row.request_id)));
      }
      state.requests = all; progressReady = true;
      for (const id of Object.keys(state.unread)) if (!valid.has(id)) delete state.unread[id];
      for (const id of Object.keys(state.newRequests)) if (!valid.has(id)) delete state.newRequests[id];
      for (const id of Object.keys(state.activity)) if (!valid.has(id) && !state.calls.some(call => call.request_id === id)) delete state.activity[id];
      for (const id of [...notices.keys()]) if (id.startsWith('request:') && !valid.has(id.slice(8))) closeNotice(id);
      for (const id of cursors.keys()) if (!active.some(row => row.request_id === id)) cursors.delete(id);
      const reads = await Promise.allSettled(active.map(request => messagesFor(request, epoch)));
      if (!current(epoch)) return;
      const failed = reads.find(result => result.status === 'rejected' && result.reason?.name !== 'AbortError');
      if (failed) await presenceError(failed.reason, epoch);
      if (current(epoch)) saveProgress();
    } catch (error) { await presenceError(error, epoch); }
    finally { if (current(epoch)) polling = false; }
  }
  async function pollCalls() {
    if (!media?.invitations || mediaUnavailable || callsPolling || !key || !staff(state.profile)) return;
    callsPolling = true; const epoch = generation, signal = controller.signal;
    const live = state.calls.filter(call => Date.parse(call.expires_at) > now());
    for (const call of state.calls) if (!live.includes(call)) closeNotice('call:' + call.call_key);
    state.calls = live; syncRinging();
    try {
      const invitations = []; let after = '0', more = true;
      while (more && current(epoch)) {
        const page = await media.invitations(after, signal);
        if (!current(epoch)) return;
        invitations.push(...page.items); more = page.has_more;
        if (more && cursorInteger(page.next_cursor) <= cursorInteger(after)) throw new Error('INVALID_SUPPORT_CURSOR');
        after = String(page.next_cursor);
      }
      const calls = invitations.filter(row => String(row.to_user_id) === String(state.profile.user_id) && row.status === 'invited'
        && row.requires_response !== false && Date.parse(row.expires_at) > now()).map(row => {
        const request = state.requests.find(request => request.conversation_id === row.conversation_id);
        return { ...row, call_key: row.type + ':' + row.id, request_id: row.request_id || request?.request_id,
          service_type: row.service_type || request?.service_type };
      }).filter(row => supportRoles(state.profile).includes(row.service_type));
      const nextKeys = new Set(calls.map(row => row.call_key));
      for (const id of callKeys) if (!nextKeys.has(id)) { closeNotice('call:' + id); silencedCalls.delete(id); }
      state.calls = calls;
      for (const call of calls) if (!callKeys.has(call.call_key)) {
        if (call.request_id) state.activity[call.request_id] = { ...state.activity[call.request_id], at: now(), preview: { type: call.mode === 'video' ? 'videoCall' : 'audioCall', text: '' } };
        alert({ ...call, kind: 'call', at: now() });
      }
      callKeys = nextKeys; syncRinging();
    } catch (error) {
      if (!current(epoch) || error.name === 'AbortError') return;
      if ([401,403,404].includes(error.status)) {
        for (const id of callKeys) closeNotice('call:' + id);
        state.calls = []; callKeys.clear(); syncRinging();
        if (error.status === 404) mediaUnavailable = true;
      }
    } finally { if (current(epoch)) callsPolling = false; }
  }
  function start(identity) {
    stop(); if (!identity) { readyTask = Promise.resolve(null); return readyTask; } key = identity; controller = new AbortController(); const epoch = generation, signal = controller.signal;
    state.loading = true;
    readyTask = (async () => {
      try {
        const profile = await api.me(signal);
        if (!current(epoch)) return null;
        applyProfile(profile);
        if (!staff(state.profile)) return null;
        pollTimer = setInterval(() => void poll(), pollMs);
        heartbeatTimer = setInterval(() => void heartbeat(), heartbeatMs);
        if (media?.invitations) { callTimer = setInterval(() => void pollCalls(), callPollMs); void pollCalls(); }
        void poll();
        if (supportRoles(profile).some(role => supportAccepting(profile, role))) void heartbeat();
        return profile;
      } catch (error) { if (current(epoch) && error.name !== 'AbortError') state.error = error.message; return null; }
      finally { if (current(epoch)) state.loading = false; }
    })();
    return readyTask;
  }
  function setSoundEnabled(enabled) {
    state.soundEnabled = !!enabled;
    try { if (soundKey) storage?.setItem(soundKey, String(state.soundEnabled)); } catch { /* Optional preference. */ }
    syncRinging();
  }
  function syncSoundPreference() {
    try { state.soundEnabled = storage?.getItem(soundKey) !== 'false'; } catch { /* Retain the current preference. */ }
    syncRinging();
  }
  async function unlockSound() {
    if (!state.soundEnabled || !staff(state.profile)) return false;
    const epoch = generation, unlocked = await sound?.unlock();
    if (current(epoch)) { state.soundReady = !!unlocked; syncRinging(); }
    return !!unlocked;
  }
  function counts() {
    const result = { support_agent: 0, expert: 0 }, ids = new Set([...Object.keys(state.unread), ...Object.keys(state.newRequests)]);
    for (const id of ids) result[(state.unread[id] || state.newRequests[id]).service_type]++;
    for (const call of state.calls) if (!ids.has(call.request_id)) { ids.add(call.request_id); result[call.service_type]++; }
    return result;
  }
  function nextNotice() {
    return (state.calls.length ? { ...state.calls[0], kind: 'call' } : null) || state.recent.find(event => state.unread[event.request_id] || state.newRequests[event.request_id])
      || (state.requests.find(row => row.status === 'active') ? { ...state.requests.find(row => row.status === 'active'), kind:'assigned' } : null);
  }
  return { state, start, stop, ready: async () => { const epoch = generation; await readyTask; return current(epoch) ? state.profile : null; }, setAccepting, heartbeat, poll, pollCalls, silenceCall, markRead, setView, clearView, reveal, counts, nextNotice, setSoundEnabled, syncSoundPreference, unlockSound };
}
