const dateFormatter = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' });

export function historyDateKey(value) {
  if (value == null || value === '') return '';
  const legacy = typeof value === 'string' && /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(value);
  const date = new Date(legacy ? value.replace(' ', 'T') + '+08:00' : value);
  if (!Number.isFinite(date.getTime())) return '';
  const parts = Object.fromEntries(dateFormatter.formatToParts(date).map(part => [part.type, part.value]));
  return parts.year + '-' + parts.month + '-' + parts.day;
}

export function historyText(message) {
  return (message.parts || []).filter(part => part.type === 'text' && typeof part.text === 'string').map(part => part.text).join('\n');
}

export function matchesHistoryMessage(message, { keyword = '', date = '', type = 'all' } = {}) {
  if (date && historyDateKey(message.created_at) !== date) return false;
  if (type !== 'all' && !(message.parts || []).some(part => part.type === type && (type !== 'text' || part.text?.trim()))) return false;
  return !keyword.trim() || historyText(message).toLocaleLowerCase().includes(keyword.trim().toLocaleLowerCase());
}

export function historyExcerpt(message, keyword = '') {
  const text = historyText(message), needle = keyword.trim(), index = text.toLocaleLowerCase().indexOf(needle.toLocaleLowerCase());
  const start = needle && index > 80 ? index - 80 : 0;
  return (start ? '…' : '') + text.slice(start, start + 320) + (text.length > start + 320 ? '…' : '');
}

export function highlightedHistoryText(text, keyword) {
  const needle = keyword.trim();
  if (!needle) return [{ text, match: false }];
  const lower = text.toLocaleLowerCase(), target = needle.toLocaleLowerCase(), parts = [];
  let start = 0, index = lower.indexOf(target);
  while (index >= 0) {
    if (index > start) parts.push({ text: text.slice(start, index), match: false });
    parts.push({ text: text.slice(index, index + needle.length), match: true });
    start = index + needle.length; index = lower.indexOf(target, start);
  }
  if (start < text.length) parts.push({ text: text.slice(start), match: false });
  return parts;
}

function sequence(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return BigInt(value);
  if (typeof value === 'string' && /^\d+$/.test(value)) return BigInt(value);
  throw new Error('INVALID_HISTORY_CURSOR');
}

/** Hardware messages form an immutable contiguous ledger. Read reverse windows through the existing forward API. */
export async function readConversationHistoryPage(api, id, before, signal) {
  const end = before ? sequence(before) : sequence((await api.detail(id, signal)).last_seq) + 1n;
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
  if (end <= 1n) return { items: [], next_before: '1', has_more: false };
  const after = end > 101n ? end - 101n : 0n;
  const page = await api.messages(id, String(after), signal, 100);
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
  return {
    items: page.items.filter(message => sequence(message.seq) < end).reverse(),
    next_before: String(after + 1n),
    has_more: after > 0n,
  };
}

/** Page an authorized transcript snapshot; do not infer a staff assignment's read cutoff from conversation metadata. */
export function readLoadedHistoryPage(messages, before, signal) {
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
  const end = before === '' ? messages.length : Number(before);
  if (!Number.isSafeInteger(end) || end < 0 || end > messages.length) throw new Error('INVALID_HISTORY_CURSOR');
  const start = Math.max(0, end - 100);
  return { items: messages.slice(start, end).reverse(), next_before: String(start), has_more: start > 0 };
}

export function legacyHistoryMessage(message, index, sessionId) {
  let content = message.content;
  if (typeof content === 'string') { try { content = JSON.parse(content); } catch { /* Plain text. */ } }
  const parts = Array.isArray(content)
    ? content.filter(part => part && ['text', 'tool', 'tool_result', 'image', 'video', 'file', 'audio', 'location'].includes(part.type)).map(part => ['tool', 'tool_result'].includes(part.type) ? { ...part, type: 'text' } : part)
    : [{ type: 'text', text: typeof content?.content === 'string' ? content.content : typeof message.content === 'string' ? message.content : '' }];
  return {
    id: sessionId + '-' + index, seq: String(index + 1), legacyIndex: index,
    sender_type: message.chatType === 1 ? 'user' : message.chatType === 3 ? 'tool' : 'assistant',
    created_at: message.createdAt,
    parts: message.audioId ? [...parts, { type: 'audio', legacy_audio_id: message.audioId }] : parts,
  };
}
