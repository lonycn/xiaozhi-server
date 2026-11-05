import { describe, it, expect, vi, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { nextTick } from 'vue';
import Esp32DevicePanel from '../src/components/Esp32DevicePanel.vue';
import { createEsp32DeviceClient } from '../src/apis/module/esp32DeviceClient.mjs';
const wrappers = [];
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.useRealTimers(); vi.restoreAllMocks(); });
function panel(client, props = {}) { const w = mount(Esp32DevicePanel, { attachTo: document.body, props: { deviceId: 'd1', token: 'user-a', client, ...props } }); wrappers.push(w); return w; }
describe('ESP32 read-only panel', () => {
  it('shows owner status and capabilities, and emits history on request', async () => {
    const client = { status: vi.fn().mockResolvedValue({ state: 'online', capabilities: '["self.get_device_status"]' }), sessions: vi.fn().mockResolvedValue([{ id: 's1' }]) };
    const w = panel(client); await flushPromises();
    expect(w.text()).toContain('在线'); expect(w.text()).toContain('self.get_device_status');
    await w.findAll('button').find(b => b.text() === '查看会话').trigger('click');
    expect(w.emitted('show-history')[0][0]).toEqual([{ id: 's1' }]);
    expect(client.status.mock.calls[0][1]).toBe('user-a');
  });
  it('keeps the same history button connected and clickable during polling', async () => {
    vi.useFakeTimers();
    let finishRefresh;
    const client = {
      status: vi.fn().mockResolvedValueOnce({ state: 'online' }).mockImplementationOnce(() => new Promise(resolve => { finishRefresh = resolve; })),
      sessions: vi.fn().mockResolvedValue([{ id: 's1' }]),
    };
    const w = panel(client);
    await nextTick(); await nextTick(); await nextTick();
    const button = w.findAll('button').find(b => b.text() === '查看会话');
    expect(button).toBeTruthy();
    const originalNode = button.element;
    await vi.advanceTimersByTimeAsync(10000);
    expect(client.status).toHaveBeenCalledTimes(2);
    expect(originalNode.isConnected).toBe(true);
    expect(w.findAll('button').find(b => b.text() === '查看会话').element).toBe(originalNode);
    await button.trigger('click');
    expect(w.emitted('show-history')[0][0]).toEqual([{ id: 's1' }]);
    finishRefresh({ state: 'online' });
    await nextTick(); await nextTick(); await nextTick();
    expect(originalNode.isConnected).toBe(true);
  });
  it('clears visible data immediately when the identity changes', async () => {
    const client = { status: vi.fn((_id, token) => token === 'user-a' ? Promise.resolve({ state: 'online' }) : new Promise(() => {})), sessions: vi.fn().mockResolvedValue([{ id: 's1' }]) };
    const w = panel(client); await flushPromises();
    expect(w.text()).toContain('查看会话');
    await w.setProps({ token: 'user-b' });
    expect(w.text()).not.toContain('查看会话');
    expect(w.text()).not.toContain('在线');
  });
  it('hides the panel when the feature route is unavailable', async () => {
    const w = panel({ status: vi.fn().mockRejectedValue({ status: 404 }), sessions: vi.fn() });
    await flushPromises(); expect(w.text()).toBe('');
  });
  it('never applies a late response from a previous account', async () => {
    let resolveOld;
    const client = { status: vi.fn((_id, token) => token === 'user-a' ? new Promise(r => { resolveOld = r; }) : Promise.resolve({ state: 'offline' })), sessions: vi.fn().mockResolvedValue([]) };
    const w = panel(client); await w.setProps({ token: 'user-b' }); await flushPromises();
    resolveOld({ state: 'online' }); await flushPromises();
    expect(w.text()).toContain('离线'); expect(w.text()).not.toContain('在线');
  });
  it('accepts raw and wrapped Java responses and propagates a disabled route', async () => {
    const fetchImpl = vi.fn().mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ state: 'online' }) }).mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ code: 0, data: [] }) }).mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({}) });
    const api = createEsp32DeviceClient({ baseUrl: '/xiaozhi', fetchImpl });
    expect((await api.status('a/b', 't')).state).toBe('online'); expect(await api.sessions('a/b', 't')).toEqual([]);
    await expect(api.status('d', 't')).rejects.toMatchObject({ status: 404 });
    expect(fetchImpl.mock.calls[0][0]).toContain('a%2Fb');
  });
});
