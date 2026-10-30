import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Workspace from '../src/views/SupportWorkspace.vue';
const api = vi.hoisted(() => ({ me: vi.fn(), presence: vi.fn(), queue: vi.fn(), assigned: vi.fn(), agents: vi.fn(), action: vi.fn(), send: vi.fn(), profile: vi.fn() }));
const conversation = vi.hoisted(() => ({ messages: vi.fn(), detail: vi.fn() }));
const state = vi.hoisted(() => ({ userInfo: {} }));
vi.mock('../src/apis/module/support', () => ({ default: api }));
vi.mock('../src/apis/module/conversation', () => ({ default: conversation }));
vi.mock('../src/store', () => ({ default: { state } }));
const page = (items = []) => ({ items, next_cursor: '0', has_more: false });
const profile = { user_id: '9223372036854775806', enabled: true, accepting: false };
let wrapper;
function mountDesk() { wrapper = mount(Workspace, { global: { mocks: { $t: key => key }, stubs: { RouterLink: true, ElAlert: true, ElSwitch: true, ElButton: { template: '<button><slot /></button>' }, ElInput: true, ElInputNumber: true, ElSelect: true, ElOption: true, ElTable: true, ElTableColumn: true } } }); return wrapper; }
beforeEach(() => { vi.useFakeTimers(); vi.resetAllMocks(); state.userInfo = {}; api.me.mockResolvedValue(profile); api.presence.mockImplementation(async accepting => ({ ...profile, accepting })); api.queue.mockResolvedValue(page()); api.assigned.mockResolvedValue(page()); api.agents.mockResolvedValue(page()); conversation.messages.mockResolvedValue(page()); conversation.detail.mockResolvedValue({ title: '咨询' }); });
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.useRealTimers(); });
describe('support desk', () => {
  it('allows profile management without granting administrator queue or conversation access', async () => {
    state.userInfo = { superAdmin: '1' }; api.me.mockResolvedValue(null); mountDesk(); await flushPromises();
    expect(api.agents).toHaveBeenCalled(); expect(api.queue).not.toHaveBeenCalled(); expect(conversation.messages).not.toHaveBeenCalled(); expect(api.presence).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('support.profiles'); expect(wrapper.text()).not.toContain('support.mine');
  });
  it('denies an ordinary account without fetching staff data', async () => {
    api.me.mockResolvedValue(null); mountDesk(); await flushPromises(); expect(wrapper.text()).toContain('support.denied'); expect(api.queue).not.toHaveBeenCalled(); expect(api.agents).not.toHaveBeenCalled();
  });
  it('heartbeats, pauses acceptance and cleans up timers', async () => {
    mountDesk(); await flushPromises(); expect(api.presence).toHaveBeenLastCalledWith(false);
    await wrapper.vm.updatePresence(true); await vi.advanceTimersByTimeAsync(15000); expect(api.presence).toHaveBeenLastCalledWith(true);
    await wrapper.vm.updatePresence(false); expect(wrapper.vm.accepting).toBe(false);
    wrapper.unmount(); wrapper = null; const count = api.presence.mock.calls.length; await vi.advanceTimersByTimeAsync(60000); expect(api.presence).toHaveBeenCalledTimes(count); expect(api.presence).toHaveBeenLastCalledWith(false);
  });
  it('loads history and retains failed drafts with a stable retry identifier', async () => {
    mountDesk(); await flushPromises(); const request = { request_id: 'r1', conversation_id: 'c1', status: 'active' }; await wrapper.vm.selectRequest(request); wrapper.vm.draft = '检查传感器';
    api.send.mockRejectedValueOnce(new Error('断开')).mockResolvedValueOnce({ id: 'm', seq: '1', parts: [], sender_type: 'support_agent' });
    await wrapper.vm.send(); expect(wrapper.vm.draft).toBe('检查传感器'); const id = api.send.mock.calls[0][1].client_message_id;
    await wrapper.vm.send(); expect(api.send.mock.calls[1][1].client_message_id).toBe(id); expect(wrapper.vm.messages).toHaveLength(1); expect(wrapper.vm.draft).toBe('');
    api.action.mockResolvedValue({ ...request, status: 'resolved' }); await wrapper.vm.end('resolve'); expect(wrapper.vm.active).toBe(false);
  });
  it('restores assigned requests and polls new messages without cursor precision loss', async () => {
    const request = { request_id: 'r1', conversation_id: 'c1', status: 'active' }; api.assigned.mockResolvedValue(page([request])); mountDesk(); await flushPromises(); expect(wrapper.vm.assigned).toEqual([request]); await wrapper.vm.selectRequest(request);
    conversation.messages.mockResolvedValue(page([{ id: 'new', seq: '9223372036854775806', sender_type: 'user', parts: [] }])); await vi.advanceTimersByTimeAsync(4000); expect(wrapper.vm.messages[0].id).toBe('new');
    await vi.advanceTimersByTimeAsync(4000); expect(conversation.messages).toHaveBeenLastCalledWith('c1', '9223372036854775806'); expect(wrapper.vm.messages).toHaveLength(1);
  });
  it('blocks switching during history load and retains a separate draft for each request', async () => {
    mountDesk(); await flushPromises();
    const first = { request_id: 'first', conversation_id: 'c1', status: 'active' };
    const second = { request_id: 'second', conversation_id: 'c2', status: 'active' };
    let finish; conversation.messages.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const pending = wrapper.vm.selectRequest(first); await wrapper.vm.selectRequest(second); expect(wrapper.vm.selected.request_id).toBe('first');
    finish(page()); await pending; wrapper.vm.draft = '草稿一'; await wrapper.vm.selectRequest(second); expect(wrapper.vm.draft).toBe('');
    await wrapper.vm.selectRequest(first); expect(wrapper.vm.draft).toBe('草稿一');
    api.action.mockResolvedValue({ ...first, status: 'queued' }); await wrapper.vm.end('requeue'); expect(api.action).toHaveBeenLastCalledWith('first', 'requeue'); expect(wrapper.vm.active).toBe(false);
  });

  it('claims only while accepting and opens the claimed request history', async () => {
    mountDesk(); await flushPromises(); const request = { request_id: 'r', conversation_id: 'c', status: 'queued' };
    await wrapper.vm.claim(request); expect(api.action).not.toHaveBeenCalled();
    await wrapper.vm.updatePresence(true); api.action.mockResolvedValue({ ...request, status: 'active' });
    await wrapper.vm.claim(request); expect(api.action).toHaveBeenCalledWith('r', 'claim'); expect(wrapper.vm.selected.status).toBe('active'); expect(conversation.messages).toHaveBeenCalledWith('c', '0');
  });

  it('rechecks a staff 403, revokes the desk, stops polling and preserves all drafts', async () => {
    mountDesk(); await flushPromises();
    const request = { request_id: 'r', conversation_id: 'c', status: 'active' };
    await wrapper.vm.selectRequest(request); wrapper.vm.draft = '撤权前未发送的内容';
    wrapper.vm.queue = [{ ...request, status: 'queued' }]; wrapper.vm.assigned = [request];
    let finishPresence; api.presence.mockImplementationOnce(() => new Promise(resolve => { finishPresence = resolve; }));
    const presence = wrapper.vm.updatePresence(true);
    api.me.mockResolvedValue(null); api.queue.mockRejectedValue(Object.assign(new Error('权限已撤销'), { status: 403, code: 'SUPPORT_PERMISSION_REQUIRED' }));
    await vi.advanceTimersByTimeAsync(4000);
    expect(api.me).toHaveBeenCalledTimes(2); expect(wrapper.vm.profile).toBeNull(); expect(wrapper.vm.accepting).toBe(false);
    expect(wrapper.vm.queue).toEqual([]); expect(wrapper.vm.assigned).toEqual([]); expect(wrapper.vm.selected).toBeNull();
    expect(wrapper.vm.drafts.r).toBe('撤权前未发送的内容'); expect(wrapper.text()).toContain('support.denied');
    finishPresence({ ...profile, accepting: true }); await presence; expect(wrapper.vm.profile).toBeNull();
    const queueCalls = api.queue.mock.calls.length, presenceCalls = api.presence.mock.calls.length;
    await vi.advanceTimersByTimeAsync(60000); expect(api.queue).toHaveBeenCalledTimes(queueCalls); expect(api.presence).toHaveBeenCalledTimes(presenceCalls); expect(vi.getTimerCount()).toBe(0);
  });
  it('keeps a valid staff profile when a resource-level assignment 403 occurs', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id: 'r', conversation_id: 'c', status: 'active' }); wrapper.vm.draft = '保留草稿';
    api.send.mockRejectedValue(Object.assign(new Error('接待已转交'), { status: 403, code: 'CURRENT_ASSIGNMENT_REQUIRED' }));
    await wrapper.vm.send(); expect(api.me).toHaveBeenCalledTimes(2); expect(wrapper.vm.profile).toEqual(profile); expect(wrapper.vm.draft).toBe('保留草稿'); expect(vi.getTimerCount()).toBe(2);
  });
  it('does not treat an administrator profiles-list 403 as staff revocation', async () => {
    state.userInfo = { superAdmin: '1' }; mountDesk(); await flushPromises(); api.agents.mockRejectedValue(Object.assign(new Error('管理权限已变化'), { status: 403 }));
    await wrapper.vm.refresh(); expect(api.me).toHaveBeenCalledTimes(1); expect(wrapper.vm.profile).toEqual(profile); expect(vi.getTimerCount()).toBe(2);
  });
  it('also rechecks presence permission failures and stops the heartbeat after account disablement', async () => {
    mountDesk(); await flushPromises();
    api.presence.mockRejectedValue(Object.assign(new Error('账号已停用'), { status: 403 })); api.me.mockRejectedValue(Object.assign(new Error('账号已停用'), { status: 403 }));
    await vi.advanceTimersByTimeAsync(15000); expect(wrapper.vm.profile).toBeNull(); expect(wrapper.vm.accepting).toBe(false); expect(vi.getTimerCount()).toBe(0);
  });

});

describe('B3 support mixed messages', () => {
  const evidence = (status = 'ready') => ({ kind: 'audio', clientUploadId: 'stable-upload', status, file: { name: '录音.m4a' }, attachment: { id: '9223372036854775806', status } });
  it('sends text and media in one staff message, preserving both on failure and the message key on retry', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id: 'r', conversation_id: 'c', status: 'active' }); wrapper.vm.draft = '处理建议'; wrapper.vm.attachmentRows = [evidence()];
    api.send.mockRejectedValueOnce(new Error('断开')).mockResolvedValueOnce({ id: 'm', parts: [] });
    await wrapper.vm.send(); const payload = api.send.mock.calls[0][1]; expect(payload.parts).toEqual([{ type: 'text', text: '处理建议' }, { type: 'audio', attachment_id: '9223372036854775806' }]);
    expect(wrapper.vm.draft).toBe('处理建议'); expect(wrapper.vm.attachmentRows).toHaveLength(1); await wrapper.vm.send(); expect(api.send.mock.calls[1][1]).toEqual(payload); expect(wrapper.vm.attachmentRows).toEqual([]);
  });
  it('blocks the whole mixed message until every attachment is ready and preserves Chinese IME composition', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id: 'r', conversation_id: 'c', status: 'active' }); wrapper.vm.draft = '输入中'; wrapper.vm.attachmentRows = [evidence('processing')];
    await wrapper.vm.send(); expect(api.send).not.toHaveBeenCalled(); wrapper.vm.attachmentRows = [evidence()];
    wrapper.vm.composerKeydown({ key: 'Enter', isComposing: true, preventDefault: vi.fn() }); expect(api.send).not.toHaveBeenCalled(); expect(wrapper.vm.draft).toBe('输入中');
  });
  it('aborts pending sends on teardown and never consumes its attachment draft from a late response', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id: 'r', conversation_id: 'c', status: 'active' }); wrapper.vm.attachmentRows = [evidence()];
    let finish; api.send.mockReturnValue(new Promise(resolve => { finish = resolve; })); const vm = wrapper.vm, sending = vm.send(), signal = api.send.mock.calls[0][2];
    wrapper.unmount(); wrapper = null; expect(signal.aborted).toBe(true); finish({ id: 'late', parts: [] }); await sending; expect(vm.messages).toEqual([]); expect(vm.attachmentDrafts.r).toHaveLength(1);
  });
});

describe('B4 support annotations', () => {
  const generated={kind:'image',clientUploadId:'annotation-12',status:'ready',progress:100,attachment:{id:'13',conversation_id:'c',filename:'服务端标注.jpg',size:123,status:'ready',metadata:{annotation_id:'12',frame_id:'10'}}};
  it('stages a ready render as an ordinary ordered attachment and blocks staging after assignment loss', async () => {
    mountDesk();await flushPromises();await wrapper.vm.selectRequest({request_id:'r',conversation_id:'c',status:'active'});wrapper.vm.stageAnnotation({scopeKey:wrapper.vm.attachmentScope,row:generated});wrapper.vm.draft='检查这里';
    api.send.mockResolvedValue({id:'m',parts:[]});await wrapper.vm.send();expect(api.send.mock.calls[0][1].parts).toEqual([{type:'text',text:'检查这里'},{type:'image',attachment_id:'13'}]);
    wrapper.vm.selected.status='resolved';wrapper.vm.stageAnnotation({scopeKey:wrapper.vm.attachmentScope,row:generated});expect(wrapper.vm.attachmentRows).toEqual([]);
  });
  it('retains annotation editing permission during ordinary history polling but revokes it when assignment ends', async () => {
    mountDesk();await flushPromises();await wrapper.vm.selectRequest({request_id:'r',conversation_id:'c',status:'active'});wrapper.vm.historyLoading=true;await wrapper.vm.$nextTick();expect(wrapper.findComponent({name:'AnnotationEditor'}).props('writable')).toBe(true);
    wrapper.vm.selected.status='resolved';await wrapper.vm.$nextTick();expect(wrapper.findComponent({name:'AnnotationEditor'}).props('writable')).toBe(false);
  });
});
