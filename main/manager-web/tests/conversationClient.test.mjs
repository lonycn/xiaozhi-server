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
});
