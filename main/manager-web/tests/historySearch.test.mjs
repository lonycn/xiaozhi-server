import { describe, expect, it, vi } from 'vitest';
import { historyDateKey, historyText, matchesHistoryMessage, historyExcerpt, highlightedHistoryText, readConversationHistoryPage, readLoadedHistoryPage, legacyHistoryMessage } from '../src/utils/historySearch.mjs';

const message = (parts, created_at = '2026-11-03T16:00:00Z') => ({ id: 'message', parts, created_at });
describe('history search matching', () => {
  it('uses Beijing dates for both UTC and legacy wall-clock timestamps at the date boundary', () => {
    expect(historyDateKey('2026-11-03T15:59:59Z')).toBe('2026-11-03');
    expect(historyDateKey('2026-11-03T16:00:00Z')).toBe('2026-11-04');
    expect(historyDateKey('2026-11-04 00:00:00')).toBe('2026-11-04');
    expect(historyDateKey('bad')).toBe(''); expect(historyDateKey(null)).toBe('');
  });
  it('combines type, date and case-insensitive keywords across mixed message parts', () => {
    const mixed = message([{ type: 'image', attachment_id: '1' }, { type: 'text', text: 'Compressor 报警' }, { type: 'video', attachment_id: '2' }]);
    expect(matchesHistoryMessage(mixed, { type: 'image', date: '2026-11-04', keyword: ' compressor ' })).toBe(true);
    expect(matchesHistoryMessage(mixed, { type: 'video', keyword: '报警' })).toBe(true);
    expect(matchesHistoryMessage(mixed, { type: 'file' })).toBe(false);
    expect(matchesHistoryMessage(mixed, { type: 'image', date: '2026-11-03' })).toBe(false);
    expect(matchesHistoryMessage(message([{ type: 'image', attachment_id: '1' }]), { type: 'text' })).toBe(false);
    expect(matchesHistoryMessage(message([{ type: 'text', text: '   ' }]), { type: 'text' })).toBe(false);
  });
  it('finds a late keyword in the excerpt and highlights literal content without treating it as HTML or regex', () => {
    const row = message([{ type: 'text', text: 'prefix '.repeat(100) + '<img onerror=alert(1)> [a+b]' }]);
    const excerpt = historyExcerpt(row, '[a+b]');
    expect(excerpt).toContain('[a+b]'); expect(excerpt.startsWith('…')).toBe(true);
    expect(highlightedHistoryText('<img> [a+b] [A+B]', '[a+b]').filter(part => part.match).map(part => part.text)).toEqual(['[a+b]', '[A+B]']);
  });
  it('normalizes legacy JSON text, structured media and recorded audio while preserving the context index', () => {
    const legacy = legacyHistoryMessage({ chatType: 1, content: '{"content":"报警"}', audioId: 'audio', createdAt: '2026-11-04 12:00:00' }, 12, 'session');
    expect(legacy.id).toBe('session-12'); expect(legacy.legacyIndex).toBe(12); expect(historyText(legacy)).toBe('报警');
    expect(matchesHistoryMessage(legacy, { type: 'audio' })).toBe(true);
    expect(matchesHistoryMessage(legacyHistoryMessage({ content: '[{"type":"image","attachment_id":"1"}]' }, 0, 's'), { type: 'image' })).toBe(true);
    expect(historyText(legacyHistoryMessage({ chatType: 3, content: '[{"type":"tool_result","text":"温度 28℃"}]' }, 0, 's'))).toBe('温度 28℃');
  });
});
describe('bounded full-history reads', () => {
  it('reads descending hundred-row windows without losing 64-bit cursor precision or including newer records', async () => {
    const last = 9007199254741205n;
    const api = { detail: vi.fn().mockResolvedValue({ last_seq: String(last) }), messages: vi.fn(async (id, after) => ({ items: Array.from({length: 100}, (_, index) => ({ id: String(BigInt(after) + BigInt(index + 1)), seq: String(BigInt(after) + BigInt(index + 1)) })) })) };
    const first = await readConversationHistoryPage(api, 'c', '', undefined);
    expect(api.messages).toHaveBeenLastCalledWith('c', '9007199254741105', undefined, 100);
    expect(first.items[0].seq).toBe(String(last)); expect(first.items.at(-1).seq).toBe('9007199254741106');
    await readConversationHistoryPage(api, 'c', first.next_before);
    expect(api.messages).toHaveBeenLastCalledWith('c', '9007199254741005', undefined, 100); expect(api.detail).toHaveBeenCalledTimes(1);
  });
  it('stops at the beginning and avoids a message read for an empty conversation', async () => {
    const api = { detail: vi.fn().mockResolvedValue({ last_seq: '0' }), messages: vi.fn().mockResolvedValue({ items: [{ seq: '1' }, { seq: '2' }, { seq: '3' }] }) };
    expect((await readConversationHistoryPage(api, 'c', '')).has_more).toBe(false); expect(api.messages).not.toHaveBeenCalled();
    expect(await readConversationHistoryPage(api, 'c', '3')).toEqual({ items: [{ seq: '2' }, { seq: '1' }], next_before: '1', has_more: false });
  });
  it('does not continue after a selection has been canceled, even if a transport ignores abort', async () => {
    const controller = new AbortController();
    const api = { detail: vi.fn(async () => { controller.abort(); return { last_seq: '100' }; }), messages: vi.fn() };
    await expect(readConversationHistoryPage(api, 'c', '', controller.signal)).rejects.toMatchObject({ name: 'AbortError' });
    expect(api.messages).not.toHaveBeenCalled();
  });
});

describe('authorized staff transcript paging', () => {
  it('handles sequence gaps and released-assignment cutoffs using only the provided snapshot', () => {
    const messages = Array.from({length:230},(_,index)=>({id:String(index),seq:String(9007199254740993n+BigInt(index*3))}));
    const first = readLoadedHistoryPage(messages,'');
    expect(first.items).toHaveLength(100); expect(first.items[0].id).toBe('229'); expect(first.next_before).toBe('130');
    const second = readLoadedHistoryPage(messages,first.next_before);
    expect(second.items[0].id).toBe('129'); expect(second.next_before).toBe('30');
    const third = readLoadedHistoryPage(messages,second.next_before);
    expect(third.items).toHaveLength(30); expect(third.items.at(-1).id).toBe('0'); expect(third.has_more).toBe(false);
    expect(messages[0].id).toBe('0');
  });
  it('rejects canceled or out-of-snapshot reads and terminates an empty transcript', () => {
    expect(readLoadedHistoryPage([],'')).toEqual({items:[],next_before:'0',has_more:false});
    for(const cursor of ['-1','abc','1.5','1000']) expect(()=>readLoadedHistoryPage([{id:'one'}],cursor)).toThrow('INVALID_HISTORY_CURSOR');
    const controller=new AbortController();controller.abort();
    expect(()=>readLoadedHistoryPage([{id:'one'}],'',controller.signal)).toThrow('Aborted');
  });
});
