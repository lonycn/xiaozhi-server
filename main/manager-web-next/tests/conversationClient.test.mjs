import { describe, expect, it, vi } from 'vitest';
import { createConversationClient } from '../src/apis/module/conversationClient.mjs';

function response(status, body) { return { status, ok: status >= 200 && status < 300, json: async () => body }; }

describe('conversation transport', () => {
  it('uses the session token and preserves the server data envelope', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response(200, { code: 0, data: { id: 'saved' } }));
    const client = createConversationClient({ baseUrl: '/xiaozhi/', getToken: () => 'session-token', onUnauthorized: vi.fn(), fetchImpl });
    expect(await client.create('设备故障')).toEqual({ id: 'saved' });
    expect(fetchImpl.mock.calls[0][0]).toBe('/xiaozhi/api/v1/ai/conversations');
    expect(fetchImpl.mock.calls[0][1].headers.Authorization).toBe('Bearer session-token');
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({ title: '设备故障' });
  });

  it('handles real HTTP 401 by clearing the session', async () => {
    const onUnauthorized = vi.fn();
    const client = createConversationClient({ baseUrl: '/xiaozhi', getToken: () => '', onUnauthorized,
      fetchImpl: vi.fn().mockResolvedValue(response(401, { code: 401 })) });
    await expect(client.list()).rejects.toThrow('登录已过期');
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('reports conflicting retries instead of presenting them as success', async () => {
    const client = createConversationClient({ baseUrl: '/xiaozhi', getToken: () => '', onUnauthorized: vi.fn(),
      fetchImpl: vi.fn().mockResolvedValue(response(409, { code: 409, msg: 'CLIENT_MESSAGE_ID_REUSED' })) });
    await expect(client.send('id', {})).rejects.toThrow('消息编号冲突');
  });

  it('classifies an application 404 only for the optional runs list', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response(200, { code: 404, msg: '资源不存在' }));
    const client = createConversationClient({ baseUrl: '/xiaozhi', getToken: () => '', onUnauthorized: vi.fn(), fetchImpl });
    await expect(client.runs('c')).rejects.toMatchObject({ status: 404 });
    await expect(client.detail('c')).rejects.toMatchObject({ status: 200 });
  });

  it('does not turn authentication, permission, server or network failures into missing runs', async () => {
    const onUnauthorized = vi.fn();
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(response(200, { code: 401, msg: 'expired' }))
      .mockResolvedValueOnce(response(200, { code: 403, msg: 'denied' }))
      .mockResolvedValueOnce(response(500, { code: 404, msg: 'unavailable' }))
      .mockRejectedValueOnce(new TypeError('offline'));
    const client = createConversationClient({ baseUrl: '/xiaozhi', getToken: () => '', onUnauthorized, fetchImpl });
    await expect(client.runs('c')).rejects.toThrow('登录已过期');
    expect(onUnauthorized).toHaveBeenCalledOnce();
    await expect(client.runs('c')).rejects.toMatchObject({ status: 200, message: 'denied' });
    await expect(client.runs('c')).rejects.toMatchObject({ status: 500 });
    await expect(client.runs('c')).rejects.toThrow('offline');
  });
});

describe('AI transport precision and cancellation', () => {
  it('reconnects with the exact decimal string cursor in both URL and Last-Event-ID, keeping token in headers', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response(200, {}));
    const client = createConversationClient({ baseUrl: '/xiaozhi', getToken: () => 'private', onUnauthorized: vi.fn(), fetchImpl });
    const abort = new AbortController();
    await client.stream('c', '9223372036854775807', abort.signal);
    const [url, opts] = fetchImpl.mock.calls[0];
    expect(url).toContain('after=9223372036854775807'); expect(url).not.toContain('private');
    expect(opts.headers['Last-Event-ID']).toBe('9223372036854775807');
    expect(opts.headers.Authorization).toBe('Bearer private'); expect(opts.signal).toBe(abort.signal);
  });
  it('propagates selection abort to JSON requests without converting it into timeout', async () => {
    const fetchImpl = vi.fn((url, { signal }) => new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    }));
    const client = createConversationClient({ baseUrl: '/xiaozhi', getToken: () => '', onUnauthorized: vi.fn(), fetchImpl });
    const abort = new AbortController(); const pending = client.messages('c', '0', abort.signal);
    abort.abort(); await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
    expect(fetchImpl.mock.calls[0][1].signal.aborted).toBe(true);
  });
});
