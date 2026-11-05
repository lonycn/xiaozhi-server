/** One audible alert per account across tabs; call ringing owns the audio lock. */
export function createStaffAudioAlerts({ sound, locks, storage, userId, now = Date.now }) {
  let wanted = false, generation = 0, pending = false, release = null;
  const heard = new Map();
  const lockName = () => 'staff-audio:' + userId();
  async function beep(event) {
    const user = userId(), epoch = generation;
    const run = lock => {
      if ((locks?.request && !lock) || !user || user !== userId() || epoch !== generation || wanted) return;
      const storageKey = 'staff-audio-heard:' + user;
      const id = `${event.kind}:${event.request_id}:${event.last_seq || event.at}`;
      let entries = Object.fromEntries(heard);
      try { entries = { ...entries, ...JSON.parse(storage?.getItem(storageKey) || '{}') }; } catch { /* Optional storage. */ }
      entries = Object.fromEntries(Object.entries(entries).filter(([,at]) => Number.isFinite(at) && now() - at < 120000).slice(-60));
      if (Object.hasOwn(entries, id) || sound?.play?.() === false) return;
      entries[id] = now(); heard.set(id, now());
      if (heard.size > 60) heard.delete(heard.keys().next().value);
      try { storage?.setItem(storageKey, JSON.stringify(entries)); } catch { /* Audio still works in this tab. */ }
    };
    try { if (locks?.request) await locks.request(lockName(), { ifAvailable: true }, run); else run(true); } catch { /* Notifications remain visible. */ }
  }
  function ringing(enabled) {
    const previous = wanted;
    wanted = !!enabled;
    if (!wanted) {
      if (previous || release) { ++generation; release?.(); release = null; sound?.stopRinging?.(); }
      return;
    }
    if (pending || release) return;
    const epoch = generation, user = userId(); pending = true;
    const run = async lock => {
      if ((locks?.request && !lock) || !wanted || epoch !== generation || user !== userId()) return;
      if (sound?.startRinging?.() === false) return;
      try { await new Promise(resolve => { release = resolve; }); }
      finally { if (epoch === generation) { release = null; sound?.stopRinging?.(); } }
    };
    void (locks?.request ? locks.request(lockName(), { ifAvailable: true }, run) : run(true))
      .catch(() => {}).finally(() => { pending = false; });
  }
  function stop() { ringing(false); ++generation; heard.clear(); }
  return { beep, ringing, stop };
}
