import { describe, it, expect, vi } from 'vitest';
import { createDeviceDebugClient } from '../src/apis/module/deviceDebugClient.mjs';
const response = body => ({ ok: true, status: 200, json: async () => body });
describe('device debug wire', () => {
  it('encodes target identity and submits literal text through the dedicated device route', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response({ code: 0, data: { id: 'command', state: 'pending' } }));
    const api = createDeviceDebugClient({ baseUrl: '/xiaozhi/', getToken: () => 'owner', fetchImpl });
    await api.target('agent', 'aa:bb', new AbortController().signal);
    expect(fetchImpl.mock.calls[0][0]).toBe('/xiaozhi/api/v1/esp32/text-input/target?agent_id=agent&mac_address=aa%3Abb');
    const payload = { conversationId: 'c', text: '<测试>\n普通文字', idempotencyKey: 'retry' };
    await api.submit('device', payload); expect(fetchImpl.mock.calls[1][0]).toBe('/xiaozhi/api/v1/esp32/devices/device/text-input');
    expect(JSON.parse(fetchImpl.mock.calls[1][1].body)).toEqual(payload);
    expect(fetchImpl.mock.calls[1][1].headers.Authorization).toBe('Bearer owner');
  });
  it('rejects transport errors, application errors and late responses from an old identity', async () => {
    let token = 'old', finish;
    const fetchImpl = vi.fn().mockReturnValueOnce(new Promise(resolve => finish = resolve));
    const api = createDeviceDebugClient({ baseUrl: '/xiaozhi', getToken: () => token, fetchImpl });
    const reading = api.command('c'); token = 'new'; finish(response({ state: 'succeeded' }));
    await expect(reading).rejects.toMatchObject({ name: 'AbortError' });
    fetchImpl.mockResolvedValueOnce({ ok: false, status: 409, json: async () => ({ code: 'session_changed' }) });
    await expect(api.command('c')).rejects.toMatchObject({ status: 409, code: 'session_changed' });
    fetchImpl.mockResolvedValueOnce(response({ code: 500, msg: 'private database details' }));
    await expect(api.command('c')).rejects.toMatchObject({ code: 'unavailable' });
  });
});
