import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Workspace from '../src/views/SupportWorkspace.vue';
const api = vi.hoisted(() => ({ me: vi.fn(), presence: vi.fn(), queue: vi.fn(), assigned: vi.fn(), agents: vi.fn(), action: vi.fn(), send: vi.fn(), profile: vi.fn(), get: vi.fn(), transfer: vi.fn() }));
const conversation = vi.hoisted(() => ({ messages: vi.fn(), detail: vi.fn() }));
const annotation = vi.hoisted(() => ({ frame: vi.fn(), list: vi.fn(), get: vi.fn() }));
const attachment = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('../src/apis/module/attachment', () => ({ default: attachment }));
const images = vi.hoisted(() => ({ blob: vi.fn(), decode: vi.fn(), release: vi.fn() }));
vi.mock('../src/apis/module/annotation', () => ({ default: annotation }));
vi.mock('../src/apis/module/annotationClient.mjs', () => ({ privateAnnotationBlob: images.blob, decodeAnnotationBlob: images.decode }));
const state = vi.hoisted(() => ({ userInfo: {} }));
vi.mock('../src/apis/module/support', () => ({ default: api }));
vi.mock('../src/apis/module/conversation', () => ({ default: conversation }));
vi.mock('../src/store', () => ({ default: { state } }));
const page = (items = []) => ({ items, next_cursor: '0', has_more: false });
const profile = { user_id: '9223372036854775806', role: 'support_agent', roles: ['support_agent'], enabled: true, accepting: false };
let wrapper;
function mountDesk(mocks = {}, props = {}) { wrapper = mount(Workspace, { props, global: { mocks: { $t: key => key, ...mocks }, stubs: { HistorySearchDrawer: true, AgentToolStatus: true, SupportSummaryPanel: true, Teleport: true, RouterLink: true, ElAlert: true, ElDialog: true, ElSwitch: true, ElButton: { template: '<button><slot /></button>' }, ElInput: true, ElInputNumber: true, ElSelect: true, ElOption: true, ElTable: true, ElTableColumn: true, ElTag: true } } }); return wrapper; }
beforeEach(() => { vi.useFakeTimers(); vi.resetAllMocks(); state.userInfo = {}; api.me.mockResolvedValue(profile); api.presence.mockImplementation(async accepting => ({ ...profile, accepting })); api.queue.mockResolvedValue(page()); api.assigned.mockResolvedValue(page()); api.agents.mockResolvedValue(page()); api.get.mockRejectedValue(Object.assign(new Error('Forbidden'), {status:403})); conversation.messages.mockResolvedValue(page()); conversation.detail.mockResolvedValue({ title: '咨询' }); annotation.list.mockResolvedValue(page()); });
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.useRealTimers(); });
describe('support desk', () => {
  it('distinguishes the operator from another staff member and customer in the same history', async () => {
    conversation.messages.mockResolvedValue(page([
      { id: 'self', seq: '1', sender_type: 'support_agent', sender_id: profile.user_id, parts: [{ type: 'text', text: 'My reply' }] },
      { id: 'colleague', seq: '2', sender_type: 'support_agent', sender_id: '9223372036854775805', parts: [{ type: 'text', text: 'Another reply' }] },
      { id: 'customer', seq: '3', sender_type: 'user', sender_id: '42', parts: [{ type: 'text', text: 'Customer message' }] },
    ]));
    mountDesk(); await flushPromises();
    await wrapper.vm.selectRequest({ request_id: 'r', conversation_id: 'c', status: 'active', assigned_agent_user_id: profile.user_id });
    expect(wrapper.findAll('.own-message')).toHaveLength(1);
    expect(wrapper.find('.own-message').attributes('data-message-id')).toBe('self');
    expect(wrapper.find('.own-message .avatar-own').exists()).toBe(true);
    expect(wrapper.find('[data-message-id="colleague"] .message-self-label').exists()).toBe(false);
    expect(wrapper.find('[data-message-id="customer"] .avatar-user').exists()).toBe(true);
  });
  it('keeps administrator profile configuration out of the business workbench', async () => {
    state.userInfo = { superAdmin: '1' }; api.me.mockResolvedValue(null); mountDesk(); await flushPromises();
    expect(api.agents).not.toHaveBeenCalled(); expect(api.queue).not.toHaveBeenCalled(); expect(conversation.messages).not.toHaveBeenCalled(); expect(api.presence).not.toHaveBeenCalled(); expect(wrapper.text()).not.toContain('support.profiles'); expect(wrapper.text()).not.toContain('support.mine');
  });
  it('denies an ordinary account without fetching staff data', async () => {
    api.me.mockResolvedValue(null); mountDesk(); await flushPromises(); expect(wrapper.text()).toContain('support.denied'); expect(api.queue).not.toHaveBeenCalled(); expect(api.agents).not.toHaveBeenCalled();
  });
  it('heartbeats, pauses acceptance and cleans up timers', async () => {
    mountDesk(); await flushPromises(); expect(api.presence).toHaveBeenLastCalledWith(false, 'support_agent', expect.anything());
    await wrapper.vm.updatePresence(true); await vi.advanceTimersByTimeAsync(15000); expect(api.presence).toHaveBeenLastCalledWith(true, 'support_agent', expect.anything());
    await wrapper.vm.updatePresence(false); expect(wrapper.vm.accepting).toBe(false);
    wrapper.unmount(); wrapper = null; const count = api.presence.mock.calls.length; await vi.advanceTimersByTimeAsync(60000); expect(api.presence).toHaveBeenCalledTimes(count); expect(api.presence).toHaveBeenLastCalledWith(false, 'support_agent', expect.anything());
  });
  it('loads history and retains failed drafts with a stable retry identifier', async () => {
    mountDesk(); await flushPromises(); const request = { request_id: 'r1', conversation_id: 'c1', status: 'active', assigned_agent_user_id: profile.user_id }; await wrapper.vm.selectRequest(request); wrapper.vm.draft = '检查传感器';
    api.send.mockRejectedValueOnce(new Error('断开')).mockResolvedValueOnce({ id: 'm', seq: '1', parts: [], sender_type: 'support_agent' });
    await wrapper.vm.send(); expect(wrapper.vm.draft).toBe('检查传感器'); const id = api.send.mock.calls[0][1].client_message_id;
    await wrapper.vm.send(); expect(api.send.mock.calls[1][1].client_message_id).toBe(id); expect(wrapper.vm.messages).toHaveLength(1); expect(wrapper.vm.draft).toBe('');
    api.action.mockResolvedValue({ ...request, status: 'resolved' }); await wrapper.vm.end('resolve'); expect(wrapper.vm.active).toBe(false);
  });
  it('restores assigned requests and polls new messages without cursor precision loss', async () => {
    const request = { request_id: 'r1', conversation_id: 'c1', status: 'active', assigned_agent_user_id: profile.user_id }; api.assigned.mockResolvedValue(page([request])); mountDesk(); await flushPromises(); expect(wrapper.vm.assigned).toEqual([request]); await wrapper.vm.selectRequest(request);
    conversation.messages.mockResolvedValue(page([{ id: 'new', seq: '9223372036854775806', sender_type: 'user', parts: [] }])); await vi.advanceTimersByTimeAsync(4000); expect(wrapper.vm.messages[0].id).toBe('new');
    await vi.advanceTimersByTimeAsync(4000); expect(conversation.messages).toHaveBeenLastCalledWith('c1', '9223372036854775806'); expect(wrapper.vm.messages).toHaveLength(1);
  });
  it('blocks switching during history load and retains a separate draft for each request', async () => {
    mountDesk(); await flushPromises();
    const first = { request_id: 'first', conversation_id: 'c1', status: 'active', assigned_agent_user_id: profile.user_id };
    const second = { request_id: 'second', conversation_id: 'c2', status: 'active', assigned_agent_user_id: profile.user_id };
    let finish; conversation.messages.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const pending = wrapper.vm.selectRequest(first); await wrapper.vm.selectRequest(second); expect(wrapper.vm.selected.request_id).toBe('first');
    finish(page()); await pending; wrapper.vm.draft = '草稿一'; await wrapper.vm.selectRequest(second); expect(wrapper.vm.draft).toBe('');
    await wrapper.vm.selectRequest(first); expect(wrapper.vm.draft).toBe('草稿一');
    api.action.mockResolvedValue({ ...first, status: 'queued' }); await wrapper.vm.end('requeue'); expect(api.action).toHaveBeenLastCalledWith('first', 'requeue'); expect(wrapper.vm.active).toBe(false);
  });

  it('claims only while accepting and opens the claimed request history', async () => {
    mountDesk(); await flushPromises(); const request = { request_id: 'r', conversation_id: 'c', status: 'queued' };
    await wrapper.vm.claim(request); expect(api.action).not.toHaveBeenCalled();
    await wrapper.vm.updatePresence(true); api.action.mockResolvedValue({ ...request, status: 'active', assigned_agent_user_id: profile.user_id });
    await wrapper.vm.claim(request); expect(api.action).toHaveBeenCalledWith('r', 'claim'); expect(wrapper.vm.selected.status).toBe('active'); expect(conversation.messages).toHaveBeenCalledWith('c', '0');
  });

  it('rechecks a staff 403, revokes the desk, stops polling and preserves all drafts', async () => {
    mountDesk(); await flushPromises();
    const request = { request_id: 'r', conversation_id: 'c', status: 'active', assigned_agent_user_id: profile.user_id };
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
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id: 'r', conversation_id: 'c', status: 'active', assigned_agent_user_id: profile.user_id }); wrapper.vm.draft = '保留草稿';
    api.send.mockRejectedValue(Object.assign(new Error('接待已转交'), { status: 403, code: 'CURRENT_ASSIGNMENT_REQUIRED' }));
    await wrapper.vm.send(); expect(api.me).toHaveBeenCalledTimes(2); expect(wrapper.vm.profile).toEqual(profile); expect(wrapper.vm.draft).toBe('保留草稿'); expect(vi.getTimerCount()).toBe(2);
  });
  it('does not poll the profile configuration endpoint while working as an expert', async () => {
    state.userInfo = { superAdmin: '1' }; mountDesk(); await flushPromises(); api.agents.mockRejectedValue(Object.assign(new Error('管理权限已变化'), { status: 403 }));
    await wrapper.vm.refresh(); expect(api.agents).not.toHaveBeenCalled(); expect(api.me).toHaveBeenCalledTimes(1); expect(wrapper.vm.profile).toEqual(profile); expect(vi.getTimerCount()).toBe(2);
  });
  it('also rechecks presence permission failures and stops the heartbeat after account disablement', async () => {
    mountDesk(); await flushPromises();
    api.presence.mockRejectedValue(Object.assign(new Error('账号已停用'), { status: 403 })); api.me.mockRejectedValue(Object.assign(new Error('账号已停用'), { status: 403 }));
    await vi.advanceTimersByTimeAsync(15000); expect(wrapper.vm.profile).toBeNull(); expect(wrapper.vm.accepting).toBe(false); expect(vi.getTimerCount()).toBe(0);
  });

  it('adds an explicitly selected internal suggestion to the current draft without sending', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id:'r', conversation_id:'c', status: 'active', assigned_agent_user_id: profile.user_id });
    wrapper.vm.draft = '客服已输入的文字';
    wrapper.findComponent({ name:'SupportSummaryPanel' }).vm.$emit('suggestion', { conversation_id:'c', text:'先核对设备铭牌与报警时间。' });
    expect(wrapper.vm.draft).toBe('客服已输入的文字\n\n先核对设备铭牌与报警时间。');
    expect(api.send).not.toHaveBeenCalled();
  });
  it('rejects suggestions from another conversation or after assignment loss', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id:'r', conversation_id:'c', status: 'active', assigned_agent_user_id: profile.user_id });
    wrapper.vm.draft = '保留';
    wrapper.vm.appendSuggestion({ conversation_id:'previous', text:'旧会话建议' });
    wrapper.vm.selected.status = 'resolved';
    wrapper.vm.appendSuggestion({ conversation_id:'c', text:'已离任客服建议' });
    expect(wrapper.vm.draft).toBe('保留'); expect(api.send).not.toHaveBeenCalled();
  });
  it('keeps an existing full draft when adding a suggestion would exceed the message limit', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id:'r', conversation_id:'c', status: 'active', assigned_agent_user_id: profile.user_id });
    wrapper.vm.draft = '字'.repeat(7999);
    wrapper.vm.appendSuggestion({ conversation_id:'c', text:'新建议' });
    expect(wrapper.vm.draft).toHaveLength(7999); expect(wrapper.vm.error).toContain('8000'); expect(api.send).not.toHaveBeenCalled();
  });
});

describe('B3 support mixed messages', () => {
  const evidence = (status = 'ready') => ({ kind: 'audio', clientUploadId: 'stable-upload', status, file: { name: '录音.m4a' }, attachment: { id: '9223372036854775806', status } });
  it('sends text and media in one staff message, preserving both on failure and the message key on retry', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id: 'r', conversation_id: 'c', status: 'active', assigned_agent_user_id: profile.user_id }); wrapper.vm.draft = '处理建议'; wrapper.vm.attachmentRows = [evidence()];
    api.send.mockRejectedValueOnce(new Error('断开')).mockResolvedValueOnce({ id: 'm', parts: [] });
    await wrapper.vm.send(); const payload = api.send.mock.calls[0][1]; expect(payload.parts).toEqual([{ type: 'text', text: '处理建议' }, { type: 'audio', attachment_id: '9223372036854775806' }]);
    expect(wrapper.vm.draft).toBe('处理建议'); expect(wrapper.vm.attachmentRows).toHaveLength(1); await wrapper.vm.send(); expect(api.send.mock.calls[1][1]).toEqual(payload); expect(wrapper.vm.attachmentRows).toEqual([]);
  });
  it('blocks the whole mixed message until every attachment is ready and preserves Chinese IME composition', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id: 'r', conversation_id: 'c', status: 'active', assigned_agent_user_id: profile.user_id }); wrapper.vm.draft = '输入中'; wrapper.vm.attachmentRows = [evidence('processing')];
    await wrapper.vm.send(); expect(api.send).not.toHaveBeenCalled(); wrapper.vm.attachmentRows = [evidence()];
    wrapper.vm.composerKeydown({ key: 'Enter', isComposing: true, preventDefault: vi.fn() }); expect(api.send).not.toHaveBeenCalled(); expect(wrapper.vm.draft).toBe('输入中');
  });
  it('aborts pending sends on teardown and never consumes its attachment draft from a late response', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest({ request_id: 'r', conversation_id: 'c', status: 'active', assigned_agent_user_id: profile.user_id }); wrapper.vm.attachmentRows = [evidence()];
    let finish; api.send.mockReturnValue(new Promise(resolve => { finish = resolve; })); const vm = wrapper.vm, sending = vm.send(), signal = api.send.mock.calls[0][2];
    wrapper.unmount(); wrapper = null; expect(signal.aborted).toBe(true); finish({ id: 'late', parts: [] }); await sending; expect(vm.messages).toEqual([]); expect(vm.attachmentDrafts.r).toHaveLength(1);
  });
});

describe('B4 support annotations', () => {
  const generated={kind:'image',clientUploadId:'annotation-12',status:'ready',progress:100,attachment:{id:'13',conversation_id:'c',filename:'服务端标注.jpg',size:123,status:'ready',metadata:{annotation_id:'12',frame_id:'10'}}};
  it('stages a ready render as an ordinary ordered attachment and blocks staging after assignment loss', async () => {
    mountDesk();await flushPromises();await wrapper.vm.selectRequest({request_id:'r',conversation_id:'c',status: 'active', assigned_agent_user_id: profile.user_id});wrapper.vm.stageAnnotation({scopeKey:wrapper.vm.attachmentScope,row:generated});wrapper.vm.draft='检查这里';
    api.send.mockResolvedValue({id:'m',parts:[]});await wrapper.vm.send();expect(api.send.mock.calls[0][1].parts).toEqual([{type:'text',text:'检查这里'},{type:'image',attachment_id:'13'}]);
    wrapper.vm.selected.status='resolved';wrapper.vm.stageAnnotation({scopeKey:wrapper.vm.attachmentScope,row:generated});expect(wrapper.vm.attachmentRows).toEqual([]);
  });
  it('keeps a live-frame editor and in-flight image load across two polls, then closes on assignment loss', async () => {
    const request = { request_id: 'r', conversation_id: 'c', status: 'active', assigned_agent_user_id: profile.user_id };
    api.assigned.mockResolvedValue(page([request]));
    annotation.frame.mockResolvedValue({ id: '10', conversation_id: 'c', frame_attachment_id: '11', width: 1000, height: 500, status: 'ready' });
    let finishImage; images.blob.mockReturnValue(new Promise(resolve => { finishImage = resolve; }));
    images.decode.mockResolvedValue({ image: { naturalWidth: 1000, naturalHeight: 500 }, release: images.release });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request);
    wrapper.vm.draft = '未发送的文字'; wrapper.vm.attachmentRows = [generated];
    wrapper.vm.openLiveFrame({ frame: { id: '10' }, conversationId: 'c' }); await flushPromises();
    const editor = wrapper.findComponent({ name: 'AnnotationEditor' });
    const signal = annotation.frame.mock.calls[0][1];
    expect(editor.vm.visible).toBe(true); expect(editor.vm.busy).toBe(true);
    await vi.advanceTimersByTimeAsync(8000);
    expect(conversation.messages).toHaveBeenCalledTimes(3);
    expect(editor.vm.visible).toBe(true); expect(editor.vm.busy).toBe(true); expect(signal.aborted).toBe(false);
    expect(wrapper.vm.draft).toBe('未发送的文字'); expect(wrapper.vm.attachmentRows).toHaveLength(1);
    finishImage(new Blob(['image'])); await flushPromises();
    expect(editor.vm.scene.frame_id).toBe('10'); expect(editor.vm.canWrite).toBe(true);
    await editor.find('.annotation-label input').setValue('现场标记'); expect(editor.vm.label).toBe('现场标记');
    api.assigned.mockResolvedValue(page()); await vi.advanceTimersByTimeAsync(4000);
    expect(editor.vm.visible).toBe(false); expect(editor.vm.writable).toBe(false); expect(signal.aborted).toBe(true);
    vi.restoreAllMocks();
  });
  it('allows switching during a background poll and ignores its late history', async () => {
    const first = { request_id: 'first', conversation_id: 'c1', status: 'active', assigned_agent_user_id: profile.user_id };
    const second = { request_id: 'second', conversation_id: 'c2', status: 'active', assigned_agent_user_id: profile.user_id };
    api.assigned.mockResolvedValue(page([first, second])); mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(first);
    let finishFrame; annotation.frame.mockReturnValue(new Promise(resolve => { finishFrame = resolve; }));
    wrapper.vm.openLiveFrame({ frame: { id: '10' }, conversationId: 'c1' }); await flushPromises();
    const editor = wrapper.findComponent({ name: 'AnnotationEditor' }), signal = annotation.frame.mock.calls[0][1];
    expect(editor.vm.visible).toBe(true);
    let finish; conversation.messages.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const polling = wrapper.vm.refresh(); await flushPromises(); expect(wrapper.vm.historyPolling).toBe(true); expect(wrapper.vm.historyLoading).toBe(false);
    await wrapper.vm.selectRequest(second); await wrapper.vm.$nextTick(); expect(editor.vm.visible).toBe(false); expect(signal.aborted).toBe(true);
    finishFrame({ id: '10', conversation_id: 'c1', status: 'ready' }); finish(page([{ id: 'stale', seq: '1', parts: [] }])); await polling;
    expect(wrapper.vm.selected.request_id).toBe('second'); expect(wrapper.vm.messages).toEqual([]); expect(wrapper.vm.historyPolling).toBe(false);
  });
  it('retains annotation editing permission during ordinary history polling but revokes it when assignment ends', async () => {
    mountDesk();await flushPromises();await wrapper.vm.selectRequest({request_id:'r',conversation_id:'c',status: 'active', assigned_agent_user_id: profile.user_id});wrapper.vm.historyLoading=true;await wrapper.vm.$nextTick();expect(wrapper.findComponent({name:'AnnotationEditor'}).props('writable')).toBe(true);
    wrapper.vm.selected.status='resolved';await wrapper.vm.$nextTick();expect(wrapper.findComponent({name:'AnnotationEditor'}).props('writable')).toBe(false);
  });
  it('keeps the docked editor and drawing draft open during a reply send while blocking annotation staging', async () => {
    const request = { request_id: 'r', conversation_id: 'c', status: 'active', assigned_agent_user_id: profile.user_id };
    annotation.frame.mockResolvedValue({ id: '10', conversation_id: 'c', frame_attachment_id: '11', width: 720, height: 1280, status: 'ready' });
    images.blob.mockResolvedValue(new Blob(['image']));
    images.decode.mockResolvedValue({ image: { naturalWidth: 720, naturalHeight: 1280 }, release: images.release });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null);
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request);
    wrapper.vm.openLiveFrame({ frame: { id: '10' }, conversationId: 'c' }); await flushPromises();
    const editor = wrapper.findComponent({ name: 'AnnotationEditor' }), instance = editor.vm;
    await editor.find('.annotation-label input').setValue('保留编辑中的标注');
    const scene = editor.vm.scene;
    editor.vm.saved = { id: '12', status: 'ready', render_attachment_id: '13' };
    editor.vm.savedSignature = editor.vm.signature; editor.vm.previewUrl = 'blob:preview';
    wrapper.vm.draft = '同时发送文字'; await wrapper.vm.$nextTick();
    expect(wrapper.find('.annotation-region').isVisible()).toBe(true);
    expect(editor.find('footer button:last-child').attributes('disabled')).toBeUndefined();
    let failSend; api.send.mockReturnValueOnce(new Promise((resolve, reject) => { failSend = reject; }));
    const pending = wrapper.vm.send(); await wrapper.vm.$nextTick();
    expect(editor.props('disabled')).toBe(false); expect(editor.props('replyDisabled')).toBe(true);
    expect(editor.vm.canWrite).toBe(true); expect(editor.vm.visible).toBe(true);
    expect(editor.find('footer button:last-child').attributes('disabled')).toBeDefined();
    await editor.vm.addToReply(); expect(attachment.get).not.toHaveBeenCalled();
    editor.vm.$emit('ready', { scopeKey: wrapper.vm.attachmentScope, row: generated });
    expect(wrapper.vm.attachmentRows).toEqual([]);
    failSend(new Error('network interrupted')); await pending; await wrapper.vm.$nextTick();
    expect(wrapper.findComponent({ name: 'AnnotationEditor' }).vm).toBe(instance);
    expect(editor.vm.visible).toBe(true); expect(wrapper.find('.annotation-region').isVisible()).toBe(true);
    expect(editor.vm.scene).toBe(scene); expect(editor.vm.label).toBe('保留编辑中的标注');
    expect(editor.props('replyDisabled')).toBe(false); expect(wrapper.vm.draft).toBe('同时发送文字');
    expect(editor.find('footer button:last-child').attributes('disabled')).toBeUndefined();
  });

  it('keeps annotation permission through reply sending and temporary staff actions but revokes a released assignment', async () => {
    mountDesk();await flushPromises();wrapper.vm.selected={request_id:'r',conversation_id:'c',status: 'active', assigned_agent_user_id: profile.user_id};wrapper.vm.draft='附上现场标注';await wrapper.vm.$nextTick();const editor=wrapper.findComponent({name:'AnnotationEditor'});
    let finish;api.send.mockReturnValueOnce(new Promise(resolve=>{finish=resolve;}));const sending=wrapper.vm.send();await wrapper.vm.$nextTick();expect(editor.props('writable')).toBe(true);expect(editor.props('disabled')).toBe(false);expect(editor.props('replyDisabled')).toBe(true);expect(editor.vm.error).toBe('');
    finish({id:'saved',parts:[{type:'text',text:'附上现场标注'}]});await sending;await wrapper.vm.$nextTick();expect(wrapper.vm.draft).toBe('');expect(editor.props('disabled')).toBe(false);expect(editor.vm.error).toBe('');
    wrapper.vm.actionBusy=true;await wrapper.vm.$nextTick();expect(editor.props('writable')).toBe(true);expect(editor.props('disabled')).toBe(true);expect(editor.vm.error).toBe('');wrapper.vm.actionBusy=false;await wrapper.vm.$nextTick();expect(editor.vm.error).toBe('');
    wrapper.vm.selected.status='resolved';await wrapper.vm.$nextTick();expect(editor.props('writable')).toBe(false);expect(editor.vm.error).toBe('annotations.denied');
  });

});

describe('expert workspace integration', () => {
  const request = { request_id:'expert-r', conversation_id:'expert-c', status: 'active', assigned_agent_user_id: profile.user_id };
  const row = { kind:'image',clientUploadId:'annotation-22',status:'ready',attachment:{id:'23',status:'ready',conversation_id:'expert-c',filename:'检查位置.jpg',metadata:{annotation_id:'22',frame_id:'20'}} };
  it('stages the editor add event once, preserves saved context after send and isolates each request', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request);
    const editor=wrapper.findComponent({name:'AnnotationEditor'});
    editor.vm.$emit('ready',{scopeKey:wrapper.vm.attachmentScope,row}); await wrapper.vm.$nextTick();
    expect(wrapper.vm.attachmentRows).toHaveLength(1); expect(wrapper.vm.savedAnnotations).toHaveLength(1); expect(api.send).not.toHaveBeenCalled();
    editor.vm.$emit('ready',{scopeKey:wrapper.vm.attachmentScope,row}); expect(wrapper.vm.attachmentRows).toHaveLength(1);
    api.send.mockResolvedValue({id:'expert-message',parts:[{type:'image',attachment_id:'23'}]}); await wrapper.vm.send();
    expect(wrapper.vm.attachmentRows).toEqual([]); expect(wrapper.vm.savedAnnotations[0].sent).toBe(true);
    await wrapper.vm.selectRequest({request_id:'other',conversation_id:'other-c',status: 'active', assigned_agent_user_id: profile.user_id}); expect(wrapper.vm.savedAnnotations).toEqual([]);
    annotation.list.mockResolvedValue(page([{ id:'22',frame_id:'20',render_attachment_id:'23',conversation_id:'expert-c',label:'检查位置',status:'ready' }]));
    await wrapper.vm.selectRequest(request); expect(wrapper.vm.savedAnnotations).toHaveLength(1);
    wrapper.vm.revokeStaff(); expect(wrapper.vm.annotationRecords).toEqual({}); expect(wrapper.vm.attachmentDrafts).toEqual({});
  });
  it('keeps the embedded media/editor instances through request selection, tool toggles and ordinary polls', async () => {
    api.assigned.mockResolvedValue(page([request])); mountDesk(); await flushPromises();
    const media=wrapper.findComponent({name:'MediaSessionOverlay'}).vm, editor=wrapper.findComponent({name:'AnnotationEditor'}).vm;
    await wrapper.vm.selectRequest(request); const layout=wrapper.findComponent({name:'ExpertWorkspaceLayout'});
    layout.vm.toolsOpen=true; await wrapper.vm.$nextTick(); layout.vm.toolsOpen=false; await wrapper.vm.refresh();
    expect(wrapper.findComponent({name:'MediaSessionOverlay'}).vm).toBe(media); expect(wrapper.findComponent({name:'AnnotationEditor'}).vm).toBe(editor);
    expect(wrapper.findComponent({name:'MediaSessionOverlay'}).props('embedded')).toBe(true); expect(wrapper.findComponent({name:'MediaSessionOverlay'}).props('adaptive')).toBe(true);
    expect(wrapper.findComponent({name:'AnnotationEditor'}).props('embedded')).toBe(true); expect(wrapper.findComponent({name:'AnnotationEditor'}).props('docked')).toBe(true);
    wrapper.vm.draft = 'resize keeps this draft'; const attachmentRows = wrapper.vm.attachmentRows;
    media.$emit('workspace-state', { active: true, hasVideo: true, portrait: true, ratio: 9 / 16, width: 720, height: 1280 }); await wrapper.vm.$nextTick();
    expect(wrapper.find('.collaboration-pane').classes()).toContain('has-video');
    expect(wrapper.find('.collaboration-communication .chat').exists()).toBe(true);
    expect(wrapper.find('.collaboration-communication .composer').exists()).toBe(true);
    expect(wrapper.find('.collaboration-communication .annotation-region .annotation-workspace').exists()).toBe(true);
    expect(wrapper.find('.collaboration-media .annotation-workspace').exists()).toBe(false);
    layout.vm.clientsCollapsed = true; await wrapper.vm.$nextTick();
    expect(wrapper.vm.draft).toBe('resize keeps this draft'); expect(wrapper.vm.attachmentRows).toBe(attachmentRows);
    expect(wrapper.findComponent({name:'MediaSessionOverlay'}).vm).toBe(media); expect(wrapper.findComponent({name:'AnnotationEditor'}).vm).toBe(editor);
    editor.$emit('visibility',true); await wrapper.vm.$nextTick(); expect(wrapper.findComponent({name:'MediaSessionOverlay'}).props('captureDisabled')).toBe(true);
  });
});

describe('persistent expert annotation history', () => {
  const request={request_id:'restored',conversation_id:'persisted-conversation',status: 'active', assigned_agent_user_id: profile.user_id};
  const saved={id:'22',frame_id:'20',render_attachment_id:'23',conversation_id:request.conversation_id,label:'已保存的现场标记',status:'ready'};
  const render={id:'23',conversation_id:request.conversation_id,filename:'已保存的现场标记.jpg',status:'ready',metadata:{annotation_id:'22',frame_id:'20'}};
  it('restores the saved and sent history from server metadata after reload without fetching private media', async () => {
    annotation.list.mockResolvedValue(page([saved]));
    conversation.messages.mockResolvedValue(page([{id:'sent',seq:'1',parts:[{type:'image',attachment_id:'23'}]}]));
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request);
    const tools=wrapper.findComponent({name:'ExpertAnnotationTools'});
    expect(tools.text()).toContain(saved.label); expect(tools.text()).toContain('expertWorkspace.sent');
    expect(annotation.list).toHaveBeenLastCalledWith(request.conversation_id,'0',expect.any(AbortSignal));
    expect(attachment.get).not.toHaveBeenCalled(); expect(images.blob).not.toHaveBeenCalled();
    const editor = wrapper.findComponent({name:'AnnotationEditor'}), open = vi.spyOn(editor.vm,'openSnapshot').mockImplementation(() => {});
    expect(tools.findAll('li button')[1].text()).toBe('annotations.saveCopy');
    await tools.findAll('li button')[1].trigger('click');
    expect(open).toHaveBeenCalledWith('22'); expect(wrapper.vm.attachmentRows).toEqual([]);
    expect(attachment.get).not.toHaveBeenCalled(); expect(api.send).not.toHaveBeenCalled();
    // Caller flags and a raw ready row cannot bypass the actual message binding.
    open.mockClear(); await wrapper.vm.addSavedAnnotation({...tools.props('items')[0],sent:false});
    await wrapper.vm.addSavedAnnotation({kind:'image',status:'ready',clientUploadId:'annotation-22',attachment:render});
    expect(open).toHaveBeenCalledTimes(2); expect(wrapper.vm.attachmentRows).toEqual([]); expect(attachment.get).not.toHaveBeenCalled();
    wrapper.vm.stageAnnotation({scopeKey:wrapper.vm.attachmentScope,row:{kind:'image',status:'ready',clientUploadId:'annotation-22',attachment:render}});
    expect(wrapper.vm.attachmentRows).toEqual([]);
  });
  it('adds an unsent server annotation through explicit validated metadata fetch and closes the drawer', async () => {
    annotation.list.mockResolvedValue(page([saved]));
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request);
    const tools=wrapper.findComponent({name:'ExpertAnnotationTools'}), layout=wrapper.findComponent({name:'ExpertWorkspaceLayout'});
    expect(tools.findAll('li button')[1].text()).toBe('expertWorkspace.addToDraft');
    layout.vm.toolsOpen=true; await wrapper.vm.$nextTick(); expect(attachment.get).not.toHaveBeenCalled();
    attachment.get.mockResolvedValue(render); await tools.findAll('li button')[1].trigger('click'); await flushPromises();
    expect(layout.vm.toolsOpen).toBe(false); expect(attachment.get).toHaveBeenCalledWith('23',expect.any(AbortSignal));
    expect(wrapper.vm.attachmentRows).toHaveLength(1); expect(wrapper.vm.savedAnnotations).toHaveLength(1); expect(tools.text()).toContain('expertWorkspace.inDraft');
    await wrapper.vm.addSavedAnnotation(tools.props('items')[0]); expect(attachment.get).toHaveBeenCalledTimes(1); expect(wrapper.vm.attachmentRows).toHaveLength(1);
  });
  it('closes the tools drawer when viewing history and reveals the same docked editor beside the live call', async () => {
    annotation.list.mockResolvedValue(page([saved]));
    let rejectRead; annotation.get.mockReturnValue(new Promise((resolve,reject)=>{rejectRead=reject;}));
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request);
    const layout=wrapper.findComponent({name:'ExpertWorkspaceLayout'}), editor=wrapper.findComponent({name:'AnnotationEditor'}), media=wrapper.findComponent({name:'MediaSessionOverlay'}).vm;
    layout.vm.layoutWidth=800; await wrapper.vm.$nextTick();
    await layout.find('.tools-rail-toggle').trigger('click');
    expect(layout.find('.tools-rail-toggle').attributes('aria-expanded')).toBe('true');
    await wrapper.findComponent({name:'ExpertAnnotationTools'}).findAll('li button')[0].trigger('click');
    expect(layout.find('.tools-rail-toggle').attributes('aria-expanded')).toBe('false');
    expect(editor.vm.visible).toBe(true); expect(editor.find('.annotation-dialog').isVisible()).toBe(true);
    expect(wrapper.findComponent({name:'MediaSessionOverlay'}).vm).toBe(media);
    expect(annotation.get).toHaveBeenCalledWith('22',expect.any(AbortSignal));
    rejectRead(new Error('read interrupted')); await flushPromises();
  });
  it('paginates metadata with a validated cursor and deduplicates an editor-ready event', async () => {
    annotation.list.mockResolvedValueOnce({items:[saved],has_more:true,next_cursor:'22'}).mockResolvedValueOnce(page([saved,{...saved,id:'24',render_attachment_id:'25',label:'另一处标记'}]));
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request);
    expect(wrapper.vm.annotationHistoryMore).toBe(true); await wrapper.vm.loadAnnotations(true);
    expect(annotation.list.mock.calls[1][1]).toBe('22'); expect(wrapper.vm.savedAnnotations).toHaveLength(2); expect(wrapper.vm.annotationHistoryMore).toBe(false);
    wrapper.vm.stageAnnotation({scopeKey:wrapper.vm.attachmentScope,row:{kind:'image',status:'ready',clientUploadId:'annotation-22',attachment:render,annotation:saved}});
    expect(wrapper.vm.savedAnnotations).toHaveLength(2); expect(wrapper.vm.attachmentRows).toHaveLength(1);
    annotation.list.mockResolvedValue({items:[],has_more:true,next_cursor:'0'}); await wrapper.vm.loadAnnotations(false);
    expect(wrapper.vm.annotationHistoryError).toBeTruthy(); expect(wrapper.vm.savedAnnotations).toHaveLength(2);
  });
  it('ignores late list/add responses after selection changes, even if transport resolves after abort', async () => {
    let finishList; annotation.list.mockReturnValueOnce(new Promise(resolve=>{finishList=resolve;}));
    mountDesk(); await flushPromises(); const selection=wrapper.vm.selectRequest(request); await flushPromises();
    const oldSignal=annotation.list.mock.calls[0][2];
    await wrapper.vm.selectRequest({request_id:'other',conversation_id:'other-conversation',status: 'active', assigned_agent_user_id: profile.user_id});
    expect(oldSignal.aborted).toBe(true); finishList(page([saved])); await selection;
    expect(wrapper.vm.savedAnnotations).toEqual([]); expect(wrapper.vm.annotationHistoryLoading).toBe(false);
    annotation.list.mockResolvedValue(page([saved])); await wrapper.vm.selectRequest(request);
    let finishAdd; attachment.get.mockReturnValueOnce(new Promise(resolve=>{finishAdd=resolve;}));
    const adding=wrapper.vm.addSavedAnnotation(wrapper.vm.savedAnnotations[0]);
    await wrapper.vm.selectRequest({request_id:'other',conversation_id:'other-conversation',status: 'active', assigned_agent_user_id: profile.user_id});
    finishAdd(render); await adding; expect(wrapper.vm.attachmentRows).toEqual([]); expect(wrapper.vm.attachmentDrafts.restored).toBeUndefined(); expect(wrapper.vm.annotationAdding).toBe('');
  });
  it('shows recoverable history errors and clears private history on an authorization failure', async () => {
    annotation.list.mockRejectedValueOnce(new Error('offline'));
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request);
    expect(wrapper.findComponent({name:'ExpertAnnotationTools'}).find('[role="alert"]').exists()).toBe(true); expect(wrapper.vm.annotationHistoryLoading).toBe(false);
    annotation.list.mockResolvedValue(page([saved])); await wrapper.vm.loadAnnotations(false); expect(wrapper.vm.annotationHistoryError).toBe(''); expect(wrapper.vm.savedAnnotations).toHaveLength(1);
    attachment.get.mockResolvedValue({...render,conversation_id:'someone-else'}); await wrapper.vm.addSavedAnnotation(wrapper.vm.savedAnnotations[0]); expect(wrapper.vm.attachmentRows).toEqual([]); expect(wrapper.vm.annotationHistoryError).toBeTruthy();
    annotation.list.mockRejectedValue(Object.assign(new Error('denied'),{status:403})); await wrapper.vm.loadAnnotations(false);
    expect(wrapper.vm.savedAnnotations).toEqual([]); expect(api.me).toHaveBeenCalledTimes(2); expect(wrapper.vm.profile).toEqual(profile);
  });
});

describe('owned support request deep links', () => {
  const request={request_id:'owned-request',conversation_id:'owned-conversation',status: 'active', assigned_agent_user_id: profile.user_id};
  it('restores only an owned assigned request and preserves unrelated query keys on selection', async () => {
    const replace=vi.fn().mockResolvedValue(); const route={path:'/support',query:{request:'owned-request',filter:'keep'}};
    api.assigned.mockResolvedValue(page([request])); mountDesk({$route:route,$router:{replace}}); await flushPromises();
    expect(wrapper.vm.selected).toEqual(request); expect(conversation.messages).toHaveBeenCalledWith('owned-conversation','0'); expect(api.action).not.toHaveBeenCalled();
    await wrapper.vm.selectRequest({request_id:'another-owned',conversation_id:'next-conversation',status: 'active', assigned_agent_user_id: profile.user_id});
    expect(replace).toHaveBeenCalledWith({path:'/support',query:{request:'another-owned',filter:'keep'}});
  });
  it('does not fetch an arbitrary deep-linked conversation or claim queue entries', async () => {
    api.assigned.mockResolvedValue(page([request])); api.queue.mockResolvedValue(page([{request_id:'queued-request',conversation_id:'queued-conversation',status:'queued'}]));
    mountDesk({$route:{path:'/support',query:{request:'queued-request'}},$router:{replace:vi.fn().mockResolvedValue()}}); await flushPromises();
    expect(wrapper.vm.selected).toBeNull(); expect(conversation.messages).not.toHaveBeenCalled(); expect(annotation.list).not.toHaveBeenCalled(); expect(api.action).not.toHaveBeenCalled();
  });
});

describe('separate staff desks and explicit transfer', () => {
  const request={request_id:'support-r',conversation_id:'c',status:'active',service_type:'support_agent',assigned_agent_user_id:profile.user_id};
  it('rejects the wrong desk before loading private queues or media', async () => {
    mountDesk(); await flushPromises(); await wrapper.setProps({serviceType:'expert'}); await flushPromises();
    expect(wrapper.vm.profile).toBeNull(); expect(wrapper.vm.selected).toBeNull();
    expect(api.queue.mock.calls.every(call=>call[1]==='support_agent')).toBe(true);
    expect(wrapper.findComponent({name:'MediaSessionOverlay'}).exists()).toBe(false);
  });
  it('filters the expert queue and fences old media, drafts and transfer state on route changes', async () => {
    api.me.mockResolvedValue({...profile,roles:['expert','support_agent']});
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request); wrapper.vm.draft='旧草稿';
    wrapper.vm.attachmentRows=[{kind:'image',attachment:{id:'old'}}]; wrapper.vm.openTransfer();
    const signal=wrapper.vm.selectionController.signal;
    await wrapper.setProps({serviceType:'expert'}); await flushPromises();
    expect(signal.aborted).toBe(true); expect(wrapper.vm.selected).toBeNull(); expect(wrapper.vm.drafts).toEqual({}); expect(wrapper.vm.attachmentDrafts).toEqual({}); expect(wrapper.vm.transferVisible).toBe(false);
    expect(api.queue).toHaveBeenLastCalledWith('0','expert',expect.any(AbortSignal));
    await wrapper.vm.selectRequest(request); expect(wrapper.vm.selected).toBeNull();
  });
  it('cannot reply or transfer merely because an account is administrator or has the other assignment', async () => {
    state.userInfo={superAdmin:1}; mountDesk(); await flushPromises();
    await wrapper.vm.selectRequest({...request,assigned_agent_user_id:'another'}); wrapper.vm.draft='不能发';
    await wrapper.vm.send(); wrapper.vm.openTransfer(); expect(api.send).not.toHaveBeenCalled(); expect(wrapper.vm.transferVisible).toBe(false);
    expect(wrapper.vm.active).toBe(false);
  });
  it('only transfers after confirmation and preserves failed draft and idempotency for retry', async () => {
    mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request); wrapper.vm.draft='继续保留回复'; wrapper.vm.openTransfer();
    expect(api.transfer).not.toHaveBeenCalled(); wrapper.vm.transferDraft.target_agent_user_id='9223372036854775806'; wrapper.vm.transferDraft.reason='进一步检查';
    api.transfer.mockRejectedValueOnce(new Error('断开')).mockResolvedValueOnce({...request,service_type:'expert',status:'queued',assigned_agent_user_id:null});
    await wrapper.vm.confirmTransfer(); expect(wrapper.vm.draft).toBe('继续保留回复'); expect(wrapper.vm.transferVisible).toBe(true); expect(wrapper.vm.transferError).toBe('断开');
    const sent=api.transfer.mock.calls[0][1]; expect(sent).toMatchObject({service_type:'expert',target_agent_user_id:'9223372036854775806',reason:'进一步检查'});
    await wrapper.vm.confirmTransfer(); expect(api.transfer.mock.calls[1][1].client_request_id).toBe(sent.client_request_id);
    expect(wrapper.vm.selected).toBeNull(); expect(wrapper.vm.transferVisible).toBe(false); expect(wrapper.vm.active).toBe(false);
  });
  it('aborts an in-flight transfer and ignores late success after changing desks', async () => {
    api.me.mockResolvedValue({...profile,roles:['expert','support_agent']}); mountDesk(); await flushPromises(); await wrapper.vm.selectRequest(request); wrapper.vm.openTransfer();
    let finish; api.transfer.mockReturnValue(new Promise(resolve=>finish=resolve)); const pending=wrapper.vm.confirmTransfer(); const signal=api.transfer.mock.calls[0][2];
    await wrapper.setProps({serviceType:'expert'}); await flushPromises(); finish({...request,status:'queued',service_type:'expert'}); await pending;
    expect(signal.aborted).toBe(true); expect(wrapper.vm.serviceType).toBe('expert'); expect(wrapper.vm.selected).toBeNull(); expect(wrapper.vm.transferVisible).toBe(false);
  });
});

describe('staff chat history finder', () => {
  const dual = { ...profile, roles: ['support_agent', 'expert'] };
  const requestFor = (service_type = 'support_agent', request_id = 'history-request') => ({ request_id, conversation_id: 'history-conversation', status: 'active', service_type, assigned_agent_user_id: profile.user_id });
  const rows = (count = 135) => Array.from({length: count}, (_, index) => ({
    id: 'history-' + index, seq: String(9007199254740000n + BigInt(index)),
    sender_type: index % 2 ? 'support_agent' : 'user', created_at: '2026-11-03T16:00:00Z',
    parts: [{ type: 'text', text: '记录 ' + index }, ...(index === 10 ? [{type: 'video', attachment_id: 'video-10'}] : [])],
  }));
  async function selectedDesk(serviceType = 'support_agent', request = requestFor(serviceType), messages = rows()) {
    api.me.mockResolvedValue(dual); api.presence.mockImplementation(async accepting => ({ ...dual, accepting }));
    conversation.messages.mockResolvedValue(page(messages));
    conversation.detail.mockResolvedValue({title:'历史咨询', last_seq:'9223372036854775807'});
    mountDesk({}, {serviceType}); await flushPromises(); await wrapper.vm.selectRequest(request); await wrapper.vm.$nextTick();
  }
  it.each(['support_agent', 'expert'])('provides right-side and chat-heading entry points for the %s desk, paging only the authorized transcript', async serviceType => {
    await selectedDesk(serviceType);
    const calls = conversation.messages.mock.calls.length;
    await wrapper.find('[data-workspace-region="tools"] .history-search-entry').trigger('click');
    const finder = wrapper.findComponent({name:'HistorySearchDrawer'});
    expect(finder.exists()).toBe(true); expect(finder.props('scopeKey')).toBe(wrapper.vm.attachmentScope);
    const first = await finder.props('loadPage')('', new AbortController().signal);
    const earlier = await finder.props('loadPage')(first.next_before, new AbortController().signal);
    expect(first.items).toHaveLength(100); expect(first.items[0].id).toBe('history-134');
    expect(first.items[0].seq).toBe('9007199254740134'); expect(first.next_before).toBe('35');
    expect(earlier.items).toHaveLength(35); expect(earlier.items.at(-1).id).toBe('history-0'); expect(earlier.has_more).toBe(false);
    expect(wrapper.vm.messages).toHaveLength(135); expect(conversation.messages).toHaveBeenCalledTimes(calls);
    expect(conversation.detail).toHaveBeenCalledTimes(1); expect(wrapper.find('.chat-heading .history-search-entry').exists()).toBe(true);
  });
  it('opens a read-only released assignment using its authorized cutoff rather than the global last_seq', async () => {
    await selectedDesk('support_agent', {...requestFor(), status:'RELEASED'}, rows(3));
    expect(wrapper.vm.active).toBe(false); wrapper.vm.openHistorySearch();
    const history = wrapper.vm.searchHistoryPage('', new AbortController().signal);
    expect(history.items.map(row=>row.id)).toEqual(['history-2','history-1','history-0']); expect(history.has_more).toBe(false);
    expect(conversation.detail).toHaveBeenCalledTimes(1); expect(api.send).not.toHaveBeenCalled();
  });
  it('keeps an open search snapshot stable during polling, then includes new records when reopened', async () => {
    await selectedDesk(); wrapper.vm.openHistorySearch();
    wrapper.vm.messages.push({id:'newest',seq:'9007199254740135',sender_type:'expert',parts:[]});
    expect(wrapper.vm.searchHistoryPage('', new AbortController().signal).items[0].id).toBe('history-134');
    wrapper.vm.closeHistorySearch(); wrapper.vm.openHistorySearch();
    expect(wrapper.vm.searchHistoryPage('', new AbortController().signal).items[0].id).toBe('newest');
  });
  it('locates and highlights the original message without replacing the transcript, and can return to latest', async () => {
    await selectedDesk();
    wrapper.vm.draft='未发送的回复';
    wrapper.vm.attachmentRows=[{kind:'image',status:'ready',attachment:{id:'draft-image',status:'ready'}}];
    await wrapper.find('.chat-heading .history-search-entry').trigger('click');
    const node = wrapper.find('[data-message-id="history-10"]');
    const focus = vi.spyOn(node.element,'focus');
    wrapper.findComponent({name:'HistorySearchDrawer'}).vm.$emit('locate', wrapper.vm.historySearchMessages[10]);
    await flushPromises();
    expect(wrapper.find('.history-target').attributes('data-message-id')).toBe('history-10'); expect(focus).toHaveBeenCalledWith({preventScroll:true});
    expect(wrapper.vm.messages).toHaveLength(135); expect(wrapper.vm.historyVisible).toBe(false);
    expect(wrapper.vm.draft).toBe('未发送的回复'); expect(wrapper.vm.attachmentRows[0].attachment.id).toBe('draft-image');
    expect(api.send).not.toHaveBeenCalled(); expect(api.action).not.toHaveBeenCalled(); expect(api.transfer).not.toHaveBeenCalled();
    await wrapper.find('.history-context-bar button').trigger('click'); await flushPromises();
    expect(wrapper.find('.history-target').exists()).toBe(false); expect(wrapper.vm.messages.at(-1).id).toBe('history-134');
  });
  it('does not locate a stale or modified result that is outside the current search snapshot', async () => {
    await selectedDesk(); wrapper.vm.openHistorySearch();
    await wrapper.vm.locateHistoryMessage({id:'history-10',seq:'99999999'});
    await wrapper.vm.locateHistoryMessage({id:'private-other-request',seq:'1'});
    expect(wrapper.vm.historyVisible).toBe(true); expect(wrapper.vm.highlightedHistoryMessageId).toBe('');
  });
  it('clears search and highlight when another assignment opens even for the same conversation', async () => {
    await selectedDesk(); wrapper.vm.openHistorySearch();
    await wrapper.vm.locateHistoryMessage(wrapper.vm.historySearchMessages[10]);
    wrapper.vm.openHistorySearch();
    await wrapper.vm.selectRequest(requestFor('support_agent','new-assignment'));
    expect(wrapper.vm.historyVisible).toBe(false); expect(wrapper.vm.historySearchMessages).toEqual([]); expect(wrapper.vm.highlightedHistoryMessageId).toBe('');
    expect(wrapper.findComponent({name:'HistorySearchDrawer'}).exists()).toBe(false);
  });
  it('removes the finder on revocation and aborts further reads without losing the reply draft', async () => {
    await selectedDesk(); wrapper.vm.draft='撤权前的草稿'; wrapper.vm.openHistorySearch(); await wrapper.vm.$nextTick();
    const load = wrapper.findComponent({name:'HistorySearchDrawer'}).props('loadPage');
    wrapper.vm.revokeStaff(); await wrapper.vm.$nextTick();
    expect(wrapper.vm.historyVisible).toBe(false); expect(wrapper.vm.historySearchMessages).toEqual([]);
    expect(wrapper.findComponent({name:'HistorySearchDrawer'}).exists()).toBe(false);
    expect(()=>load('',new AbortController().signal)).toThrow('Aborted');
    expect(wrapper.vm.drafts['history-request']).toBe('撤权前的草稿');
  });
  it('clears search state when switching between expert and support roles', async () => {
    await selectedDesk(); wrapper.vm.openHistorySearch();
    await wrapper.setProps({serviceType:'expert'}); await flushPromises();
    expect(wrapper.vm.historyVisible).toBe(false); expect(wrapper.vm.historySearchMessages).toEqual([]); expect(wrapper.vm.highlightedHistoryMessageId).toBe('');
    expect(wrapper.vm.selected).toBeNull();
  });
  it('waits for the initial authorized history load to finish before enabling search', async () => {
    await selectedDesk(); wrapper.vm.historyLoading=true; await wrapper.vm.$nextTick(); wrapper.vm.openHistorySearch();
    expect(wrapper.vm.historyVisible).toBe(false);
    expect(wrapper.find('[data-workspace-region="tools"] .history-search-entry').attributes('disabled')).toBeDefined();
  });
});

describe('saved reception preference',()=>{
 it('restores enabled reception and does not save pause when leaving the desk',async()=>{
  api.me.mockResolvedValue({...profile,accepting:true});mountDesk();await flushPromises();
  expect(wrapper.vm.accepting).toBe(true);expect(api.presence).toHaveBeenLastCalledWith(true, 'support_agent', expect.anything());
  const writes=api.presence.mock.calls.length;wrapper.unmount();wrapper=null;
  expect(api.presence).toHaveBeenCalledTimes(writes);
 });
 it('rolls back a failed toggle to the last confirmed preference instead of silently pausing',async()=>{
  api.me.mockResolvedValue({...profile,accepting:true});mountDesk();await flushPromises();api.presence.mockRejectedValueOnce(new Error('暂时断网'));
  wrapper.vm.accepting=false;await wrapper.vm.updatePresence(false);
  expect(wrapper.vm.accepting).toBe(true);expect(wrapper.vm.error).toBe('暂时断网');
 });
});

it('closes historical search and refreshes new messages when a notification returns to the latest conversation',async()=>{
 const request={request_id:'r',conversation_id:'c',status:'active',assigned_agent_user_id:profile.user_id};
 mountDesk();await flushPromises();await wrapper.vm.selectRequest(request);
 wrapper.vm.openHistorySearch();expect(wrapper.vm.historyVisible).toBe(true);
 conversation.messages.mockResolvedValue(page([{id:'latest-customer',seq:'1',sender_type:'user',parts:[{type:'text',text:'新到的客户消息'}]}]));
 await wrapper.vm.showLatestHistory();
 expect(wrapper.vm.historyVisible).toBe(false);expect(wrapper.vm.messages.at(-1).id).toBe('latest-customer');
});
