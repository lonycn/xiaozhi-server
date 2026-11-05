import { describe, it, expect } from 'vitest';
import { readSse, mergeMessages } from '../src/utils/aiStream.mjs';

describe('AI stream', () => {
  it('parses split UTF-8, CRLF, comments and multiline data', async () => {
    const bytes = new TextEncoder().encode(': heartbeat\r\nid: 9007199254740999\r\nevent: delta\r\ndata: 你\r\ndata: 好\r\n\r\n');
    const stream = new ReadableStream({ start(c) { for (const byte of bytes) c.enqueue(Uint8Array.of(byte)); c.close(); } });
    const events = []; await readSse(stream, event => events.push(event));
    expect(events).toEqual([{ id: '9007199254740999', type: 'delta', data: '你\n好' }]);
  });
  it('replaces an existing message with its current state and parts', () => {
    expect(mergeMessages([{ id: '1', status: 'queued', parts: [] }], [{ id: '1', status: 'streaming', parts: [{ text: 'hi' }] }])).toEqual([{ id: '1', status: 'streaming', parts: [{ text: 'hi' }] }]);
  });
});

describe('stream cleanup', () => {
  it('cancels a reader waiting for data on abort', async () => {
    const controller = new AbortController(); let canceled = false;
    const stream = new ReadableStream({ cancel() { canceled = true; } });
    const pending = readSse(stream, () => { throw new Error('late frame'); }, controller.signal);
    controller.abort(); await pending;
    expect(canceled).toBe(true); expect(stream.locked).toBe(false);
  });
  it('cancels/releases the body if a frame handler fails', async () => {
    let canceled = false;
    const stream = new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('data: x\n\n')); }, cancel() { canceled = true; } });
    await expect(readSse(stream, () => { throw new Error('failed'); })).rejects.toThrow('failed');
    expect(canceled).toBe(true); expect(stream.locked).toBe(false);
  });
});
