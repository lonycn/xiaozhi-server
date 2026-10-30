// SSE frames can span arbitrary UTF-8 chunks and use either CRLF or LF.
export async function readSse(body, onEvent, signal) {
  const reader = body.getReader(), decoder = new TextDecoder();
  const cancel = () => { reader.cancel().catch(() => {}); };
  if (signal?.aborted) cancel();
  signal?.addEventListener('abort', cancel, { once: true });
  let buffer = '', event = { data: [] };
  const line = value => {
    if (!value) {
      if (!signal?.aborted && event.data.length) onEvent({ id: event.id, type: event.type || 'message', data: event.data.join('\n') });
      event = { data: [] }; return;
    }
    if (value.startsWith(':')) return;
    const colon = value.indexOf(':'); const field = colon < 0 ? value : value.slice(0, colon);
    let data = colon < 0 ? '' : value.slice(colon + 1); if (data.startsWith(' ')) data = data.slice(1);
    if (field === 'data') event.data.push(data);
    else if (field === 'event') event.type = data;
    else if (field === 'id' && !data.includes('\0')) event.id = data;
  };
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
      let match;
      while ((match = /\r\n|\r|\n/.exec(buffer))) {
        if (match[0] === '\r' && match.index === buffer.length - 1 && !done) break;
        line(buffer.slice(0, match.index)); buffer = buffer.slice(match.index + match[0].length);
      }
      if (done) break;
    }
  } finally { signal?.removeEventListener('abort', cancel); await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
export function mergeMessages(current, incoming) {
  const result = [...current];
  for (const message of incoming) { const index = result.findIndex(item => item.id === message.id); if (index < 0) result.push(message); else result[index] = message; }
  return result.sort((a, b) => {
    const left = /^\d+$/.test(String(a.seq)) ? BigInt(a.seq) : null;
    const right = /^\d+$/.test(String(b.seq)) ? BigInt(b.seq) : null;
    return left === null ? (right === null ? 0 : 1) : right === null ? -1 : left < right ? -1 : left > right ? 1 : 0;
  });
}

const statuses = { 'ai.run.queued': 'queued', 'assistant.message.created': 'queued',
  'assistant.message.reset': 'running', 'ai.run.started': 'running',
  'assistant.text.delta': 'streaming', 'assistant.message.completed': 'completed',
  'ai.run.failed': 'failed', 'ai.run.canceled': 'canceled' };

// Reconstruct text exclusively from the event journal, never append to a snapshot.
// A snapshot has no event watermark and may be either ahead of or behind SSE.
export function projectAiEvent(previous, type, data) {
  if (!statuses[type] || !/^\d+$/.test(String(data.attempt ?? ''))) return previous;
  const attempt = String(data.attempt);
  if (previous && BigInt(attempt) < BigInt(previous.attempt)) return previous;
  const fresh = !previous || BigInt(attempt) > BigInt(previous.attempt);
  let next = fresh ? { attempt, parts: [], status: 'queued', chunkSequence: '0' } : { ...previous };
  if (!fresh && ['completed', 'failed', 'canceled'].includes(next.status) && type !== 'ai.run.queued') return previous;
  if (type === 'assistant.message.reset') next = { ...next, parts: [], chunkSequence: '0' };
  if (type === 'assistant.text.delta') {
    const sequence = String(data.chunk_sequence ?? '');
    if (!/^\d+$/.test(sequence) || BigInt(sequence) <= BigInt(next.chunkSequence)) return previous;
    if (typeof data.text !== 'string') return previous;
    next.parts = [{ type: 'text', text: (next.parts[0]?.text || '') + data.text }];
    next.chunkSequence = sequence;
  }
  next.status = statuses[type];
  return next;
}

export function abortableDelay(ms, signal) {
  return new Promise(resolve => {
    if (signal.aborted) return resolve();
    const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve(); };
    const timer = setTimeout(finish, ms);
    signal.addEventListener('abort', finish, { once: true });
  });
}
