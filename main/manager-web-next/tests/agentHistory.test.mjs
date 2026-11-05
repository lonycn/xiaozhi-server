import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { reactive } from 'vue';
import { beforeEach, afterEach, it, expect, vi } from 'vitest';
import Page from '../src/views/AgentChatHistory.vue';
import { useAuthStore } from '../src/stores/auth';
const mocks = vi.hoisted(() => ({ api: { devices: vi.fn(), sessions: vi.fn(), messages: vi.fn(), download: vi.fn(), audioGrant: vi.fn(), audioUrl: vi.fn() }, route: null }));
vi.mock('vue-router', async original => ({ ...await original(), useRoute: () => mocks.route }));
vi.mock('../src/apis/module/agentHistoryClient.mjs', async original => ({ ...await original(), createAgentHistoryClient: vi.fn(() => mocks.api) }));
// Use the actual wire factory separately from the view's injected API.
const actual = await vi.importActual('../src/apis/module/agentHistoryClient.mjs');
const a = { sessionId: 'a', title: 'First conversation', chatCount: 1, macAddress: 'device-a' };
const b = { sessionId: 'b', title: 'Second conversation', chatCount: 1, macAddress: 'device-b' };
let wrapper, auth;
const render = () => wrapper = mount(Page, { global: { mocks: { $t: key => key, $router: { push: vi.fn() } }, stubs: { HistorySearchDrawer: true, ElButton: { props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot /></button>' }, LegacyIcon: true } } });
beforeEach(() => {
  setActivePinia(createPinia()); auth = useAuthStore(); auth.token = JSON.stringify({ token: 'test-token' });
  mocks.route = reactive({ query: { agentId: 'agent-a', agentName: 'Agent A' } });
  Object.values(mocks.api).forEach(fn => fn.mockReset());
  mocks.api.devices.mockResolvedValue(['device-a', 'device-b']);
  mocks.api.sessions.mockResolvedValue({ list: [a, b], total: 2 });
  mocks.api.messages.mockResolvedValue([{ content: 'Message A', chatType: 1, createdAt: '2026-11-02T01:00:00Z' }]);
});
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.restoreAllMocks(); });
it('loads and filters server pagination then loads the selected device conversation', async () => {
  render(); await flushPromises();
  expect(wrapper.text()).toContain('Message A');
  await wrapper.find('select').setValue('device-b'); await flushPromises();
  expect(mocks.api.sessions).toHaveBeenLastCalledWith('agent-a', { page: 1, limit: 20, macAddress: 'device-b' }, expect.any(AbortSignal));
  expect(mocks.api.messages).toHaveBeenLastCalledWith('agent-a', 'a', 'device-b', expect.any(AbortSignal));
  expect(wrapper.find('.history-downloads').exists()).toBe(true);
});
it('late message responses cannot replace the next selected conversation', async () => {
  let old; mocks.api.messages.mockImplementationOnce(() => new Promise(resolve => old = resolve)).mockResolvedValueOnce([{ content: 'Current B', chatType: 2 }]);
  render(); await flushPromises(); const signal = mocks.api.messages.mock.calls[0][3];
  await wrapper.findAll('.history-session')[1].trigger('click'); await flushPromises();
  expect(signal.aborted).toBe(true); expect(wrapper.text()).toContain('Current B');
  old([{ content: 'Stale A', chatType: 1 }]); await flushPromises(); expect(wrapper.text()).not.toContain('Stale A');
});
it('late session pages cannot restore another device after filtering', async () => {
  let old; mocks.api.sessions.mockImplementationOnce(() => new Promise(resolve => old = resolve)).mockResolvedValueOnce({ list: [b], total: 1 });
  render(); await flushPromises(); const signal = mocks.api.sessions.mock.calls[0][2];
  await wrapper.find('select').setValue('device-b'); await flushPromises();
  old({ list: [a], total: 1 }); await flushPromises();
  expect(signal.aborted).toBe(true); expect(wrapper.findAll('.history-session')).toHaveLength(1); expect(wrapper.find('.history-session').text()).toContain('Second conversation');
});
it('failed loading clears the spinner and can be retried; empty records disable downloads', async () => {
  mocks.api.sessions.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({ list: [], total: 0 });
  render(); await flushPromises(); expect(wrapper.find('[role="alert"]').exists()).toBe(true);
  await wrapper.find('[role="alert"] button').trigger('click'); await flushPromises();
  expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  expect(wrapper.find('.history-sessions').attributes('aria-busy')).toBe('false');
  expect(wrapper.findAll('.history-downloads button').every(button => button.attributes('disabled') !== undefined)).toBe(true);
});
it('changing identity clears records and fences a pending old reply', async () => {
  let old; mocks.api.messages.mockImplementationOnce(() => new Promise(resolve => old = resolve));
  render(); await flushPromises(); const signal = mocks.api.messages.mock.calls[0][3];
  auth.token = ''; expect(signal.aborted).toBe(true);
  old([{ content: 'Private old account', chatType: 1 }]); await flushPromises();
  expect(wrapper.text()).not.toContain('Private old account'); expect(wrapper.findAll('.history-session')).toHaveLength(0);
});
it('unmount aborts a pending export before it can trigger a download', async () => {
  let finish; mocks.api.download.mockImplementation(() => new Promise(resolve => finish = resolve));
  render(); await flushPromises(); await wrapper.find('.history-downloads button').trigger('click');
  const signal = mocks.api.download.mock.calls[0][4]; const spy = vi.fn(); const original = URL.createObjectURL; URL.createObjectURL = spy;
  wrapper.unmount(); wrapper = null; finish(new Blob(['data'])); await flushPromises();
  expect(signal.aborted).toBe(true); expect(spy).not.toHaveBeenCalled(); URL.createObjectURL = original;
});
it('wire preserves encoded device scope and scopes both export requests', async () => {
  const fetchImpl = vi.fn().mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ code: 0, data: 'grant' }) }).mockResolvedValueOnce({ ok: true, status: 200, headers: new Headers({ 'content-type': 'text/plain' }), blob: async () => new Blob(['text']) });
  const client = actual.createAgentHistoryClient({ baseUrl: '/wire', getToken: () => 'token', fetchImpl });
  await client.download('agent', 'session', '02:AA:BB:CC:DD:EE', true);
  expect(fetchImpl.mock.calls[0][0]).toContain('macAddress=02%3AAA%3ABB%3ACC%3ADD%3AEE');
  expect(fetchImpl.mock.calls[0][1].method).toBe('POST'); expect(fetchImpl.mock.calls[1][0]).toBe('/wire/agent/chat-history/download/grant/previous');
});
it('an old unauthorized response cannot log out a newer identity', async () => {
  let token = 'old', finish; const unauthorized = vi.fn();
  const client = actual.createAgentHistoryClient({ baseUrl: '/wire', getToken: () => token, onUnauthorized: unauthorized, fetchImpl: () => new Promise(resolve => finish = resolve) });
  const pending = client.devices('agent'); token = 'new'; finish({ ok: false, status: 401 });
  await expect(pending).rejects.toMatchObject({ code: 'STALE_IDENTITY' }); expect(unauthorized).not.toHaveBeenCalled();
});
it('JSON error responses are not saved as a successful history file', async () => {
  const fetchImpl = vi.fn().mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ code: 0, data: 'grant' }) }).mockResolvedValueOnce({ ok: true, status: 200, headers: new Headers({ 'content-type': 'application/json' }), json: async () => ({ code: 500 }) });
  const client = actual.createAgentHistoryClient({ baseUrl: '/wire', getToken: () => 'token', fetchImpl });
  await expect(client.download('agent', 'session', '', false)).rejects.toThrow('HISTORY_UNAVAILABLE');
});
it('embedded hardware history loads only the selected native session and aborts the previous selection', async () => {
  let old; mocks.api.messages.mockImplementationOnce(() => new Promise(resolve => old = resolve)).mockResolvedValueOnce([{ content: 'New hardware record', chatType: 2 }]);
  wrapper = mount(Page, { props: { embedded: true, historyAgentId: 'agent-a', historyAgentName: 'Agent', historyDevice: 'device-b', historySession: a }, global: { mocks: { $t: key => key }, stubs: { ElButton: true, LegacyIcon: true } } });
  await flushPromises(); const signal = mocks.api.messages.mock.calls[0][3];
  expect(mocks.api.devices).not.toHaveBeenCalled(); expect(mocks.api.sessions).not.toHaveBeenCalled(); expect(wrapper.find('.history-sidebar').exists()).toBe(false);
  await wrapper.setProps({ historySession: b }); await flushPromises();
  expect(signal.aborted).toBe(true); expect(mocks.api.messages).toHaveBeenLastCalledWith('agent-a', 'b', 'device-b', expect.any(AbortSignal));
  old([{ content: 'Old hardware record', chatType: 1 }]); await flushPromises(); expect(wrapper.text()).toContain('New hardware record'); expect(wrapper.text()).not.toContain('Old hardware record');
});

it('shows the latest ten legacy messages, reveals cached history on demand, and keeps audio row keys stable', async () => {
  mocks.api.messages.mockResolvedValue(Array.from({length: 25}, (_, i) => ({ content: `Legacy message ${i + 1}`, chatType: 1, audioId: `audio-${i + 1}` })));
  render(); await flushPromises();
  let rows = wrapper.findAll('.history-message');
  expect(rows).toHaveLength(10); expect(rows[0].text()).toContain('Legacy message 16'); expect(rows.at(-1).text()).toContain('Legacy message 25');
  const oldRow = rows[0].element;
  await wrapper.find('.message-history-status button').trigger('click'); await flushPromises();
  rows = wrapper.findAll('.history-message'); expect(rows).toHaveLength(20); expect(rows[0].text()).toContain('Legacy message 6'); expect(rows[10].element).toBe(oldRow);
  await wrapper.find('.message-history-status button').trigger('click'); await flushPromises();
  expect(wrapper.findAll('.history-message')).toHaveLength(25); expect(wrapper.find('.message-history-status button').exists()).toBe(false);
  expect(mocks.api.messages).toHaveBeenCalledTimes(1);
});

it('searches the full legacy cache and opens just the selected result context before returning to latest', async () => {
  mocks.api.messages.mockResolvedValue(Array.from({length: 50}, (_, index) => ({ content: 'Legacy result ' + index, chatType: 1, audioId: 'audio-' + index })));
  render(); await flushPromises();
  await wrapper.find('.history-search-entry').trigger('click');
  const drawer = wrapper.findComponent({name:'HistorySearchDrawer'});
  const history = await drawer.props('loadPage')('', new AbortController().signal);
  expect(history.items).toHaveLength(50); expect(history.items[0].legacyIndex).toBe(49);
  expect(history.items[0].parts.some(part => part.type === 'audio')).toBe(true);
  drawer.vm.$emit('locate', history.items.find(message => message.legacyIndex === 10)); await flushPromises();
  expect(wrapper.findAll('.history-message')).toHaveLength(10);
  expect(wrapper.find('.history-target').attributes('data-history-index')).toBe('10');
  expect(wrapper.find('.history-context-bar').exists()).toBe(true);
  await wrapper.find('.history-context-bar button').trigger('click'); await flushPromises();
  expect(wrapper.find('.history-context-bar').exists()).toBe(false); expect(wrapper.findAll('.history-message')[0].text()).toContain('Legacy result 40');
});

it('exposes the embedded session search only after a successful load and revokes readiness on a new selection', async () => {
  let finish;
  mocks.api.messages.mockImplementationOnce(() => new Promise(resolve => finish = resolve)).mockRejectedValueOnce(new Error('offline'));
  wrapper = mount(Page, { props: { embedded: true, historyAgentId: 'agent-a', historySession: a }, global: { mocks: { $t: key => key }, stubs: { HistorySearchDrawer: true, ElButton: true, LegacyIcon: true } } });
  await flushPromises();
  wrapper.vm.openHistorySearch(); await flushPromises();
  expect(wrapper.findComponent({ name: 'HistorySearchDrawer' }).exists()).toBe(false);
  expect(wrapper.emitted('history-availability').at(-1)).toEqual([false]);
  finish([{ content: 'Native searchable message', chatType: 1 }]); await flushPromises();
  expect(wrapper.emitted('history-availability').at(-1)).toEqual([true]);
  expect(wrapper.find('.history-detail-heading .history-search-entry').exists()).toBe(false);
  wrapper.vm.openHistorySearch(); await flushPromises(); expect(wrapper.findComponent({ name: 'HistorySearchDrawer' }).exists()).toBe(true);
  await wrapper.setProps({ historySession: b }); await flushPromises();
  expect(wrapper.emitted('history-availability').at(-1)).toEqual([false]);
  wrapper.vm.openHistorySearch(); await flushPromises(); expect(wrapper.findComponent({ name: 'HistorySearchDrawer' }).exists()).toBe(false);
});

it('keeps the loaded native transcript when the tool panel rerenders the same session object', async () => {
  wrapper = mount(Page, { props: { embedded: true, historyAgentId: 'agent-a', historySession: a }, global: { mocks: { $t: key => key }, stubs: { ElButton: true, LegacyIcon: true } } });
  await flushPromises();
  expect(mocks.api.messages).toHaveBeenCalledTimes(1);
  await wrapper.setProps({ historySession: { ...a } }); await flushPromises();
  expect(mocks.api.messages).toHaveBeenCalledTimes(1);
  expect(wrapper.emitted('history-availability').at(-1)).toEqual([true]);
  expect(wrapper.findAll('.history-message')).toHaveLength(1);
});
