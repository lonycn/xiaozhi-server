/** Local tones only; browser audio starts after an explicit user gesture. */
export function createStaffSound({ AudioContextImpl = globalThis.AudioContext || globalThis.webkitAudioContext, now = Date.now } = {}) {
  let context = null, unlocking = null, lastPlayed = -Infinity, ringTimer = null;
  const nodes = new Map();
  function tone(offset, frequency, duration, volume, secondFrequency) {
    const start = context.currentTime + offset, gain = context.createGain();
    gain.gain.setValueAtTime(0, start);
    gain.gain.linearRampToValueAtTime(volume, start + 0.015);
    gain.gain.exponentialRampToValueAtTime(0.001, start + duration);
    gain.connect(context.destination);
    for (const value of secondFrequency ? [frequency, secondFrequency] : [frequency]) {
      const oscillator = context.createOscillator();
      oscillator.type = 'sine'; oscillator.frequency.value = value;
      oscillator.connect(gain); nodes.set(oscillator, gain);
      oscillator.onended = () => { nodes.delete(oscillator); oscillator.disconnect(); if (![...nodes.values()].includes(gain)) gain.disconnect(); };
      oscillator.start(start); oscillator.stop(start + duration + 0.015);
    }
  }
  function ring() {
    if (context?.state !== 'running') return;
    try { tone(0, 440, 0.38, 0.12, 560); tone(0.52, 440, 0.38, 0.12, 560); } catch { /* Visual call notification remains available. */ }
  }
  function stopRinging() {
    clearInterval(ringTimer); ringTimer = null;
    for (const [node, gain] of nodes) { try { node.stop(); node.disconnect(); gain.disconnect(); } catch { /* Already ended. */ } }
    nodes.clear();
  }
  return {
    available: !!AudioContextImpl,
    async unlock() {
      if (!AudioContextImpl) return false;
      if (unlocking) return unlocking;
      unlocking = (async () => {
        try {
          if (!context || context.state === 'closed') context = new AudioContextImpl();
          if (context.state !== 'running') await context.resume();
          return context.state === 'running';
        } catch { return false; }
      })().finally(() => { unlocking = null; });
      return unlocking;
    },
    play() {
      if (ringTimer || !context || context.state !== 'running' || now() - lastPlayed < 1000) return false;
      try { tone(0, 920, 0.12, 0.09); lastPlayed = now(); return true; } catch { return false; }
    },
    startRinging() {
      if (ringTimer) return true;
      if (context?.state !== 'running') return false;
      ring(); ringTimer = setInterval(ring, 2600); return true;
    },
    stopRinging,
    close() { stopRinging(); const old = context; context = null; lastPlayed = -Infinity; if (old && old.state !== 'closed') void old.close().catch(() => {}); },
  };
}
