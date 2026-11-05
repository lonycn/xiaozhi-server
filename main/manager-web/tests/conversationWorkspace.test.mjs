import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Workspace from '../src/views/ConversationWorkspace.vue';
import { createConversationClient } from '../src/apis/module/conversationClient.mjs';
const api = vi.hoisted(() => ({ index: vi.fn(), indexOptions: vi.fn(), list: vi.fn(), create: vi.fn(), messages: vi.fn(), send: vi.fn(), detail: vi.fn(), events: vi.fn(), handoff: vi.fn(), resume: vi.fn(), runs: vi.fn(), stream: vi.fn(), runAction: vi.fn() }));
vi.mock('../src/apis/module/conversation', () => ({ default: api }));
const support = vi.hoisted(() => ({ get: vi.fn(), action: vi.fn() }));
vi.mock('../src/apis/module/support', () => ({ default: support }));
let wrapper, vm;
beforeEach(() => {
  vi.resetAllMocks();
  api.index.mockImplementation((params, signal) => api.list(params.before || 0, signal));
  api.indexOptions.mockResolvedValue({ agents: [], devices: [] });
  api.runs.mockRejectedValue(Object.assign(new Error('disabled'), { status: 404 }));
  api.list.mockResolvedValue({ items: [], has_more: false, next_cursor: 0 });
  api.detail.mockResolvedValue({ state: 'AI_ACTIVE', last_seq: '0' });
  api.events.mockResolvedValue({ items: [], next_cursor: '0', has_more: false });
  wrapper = mount(Workspace, { global: { mocks: { $t: key => key }, stubs: { HistorySearchDrawer: true, AgentChatHistory: true, LegacyIcon: true, RouterLink: true, ElDialog: true, ElInput: true, ElButton: true } } });
  vm = wrapper.vm;
});
afterEach(() => { if (wrapper.exists()) wrapper.unmount(); vi.useRealTimers(); });

describe('conversation workspace', () => {
  it('degrades the optional AI runs panel when the route returns an application 404', async () => {
    const client = createConversationClient({ baseUrl:'/xiaozhi', getToken:()=>'', onUnauthorized:vi.fn(),
      fetchImpl:vi.fn().mockResolvedValue({ status:200, ok:true, json:async()=>({ code:404, msg:'资源不存在' }) }) });
    api.runs.mockImplementation(client.runs);
    api.messages.mockResolvedValue({ items:[], next_cursor:'0', has_more:false });
    await vm.selectConversation({ id:'c' });
    expect(vm.aiAvailable).toBe(false);
    expect(vm.error).toBe('');
    expect(vm.selected.id).toBe('c');
  });

  it.each([
    ['HTTP 500', () => Promise.resolve({ status:500, ok:false, json:async()=>({code:500,msg:'server failure'}) }), 'server failure'],
    ['network failure', () => Promise.reject(new TypeError('network failure')), 'network failure'],
    ['application 403', () => Promise.resolve({ status:200, ok:true, json:async()=>({code:403,msg:'access denied'}) }), 'access denied'],
  ])('preserves %s while loading optional AI runs', async (name, fetchImpl, message) => {
    const client = createConversationClient({ baseUrl:'/xiaozhi', getToken:()=>'', onUnauthorized:vi.fn(), fetchImpl });
    api.runs.mockImplementation(client.runs);
    api.messages.mockResolvedValue({ items:[], next_cursor:'0', has_more:false });
    await vm.selectConversation({ id:'c' });
    expect(vm.error).toContain(message);
  });

  it('retains failed content and retries with the same idempotency identifier', async () => {
    vm.selected = { id: 'one' }; vm.draft = '压缩机有异响';
    api.send.mockRejectedValueOnce(new Error('连接中断')).mockResolvedValueOnce({ id: 'saved', parts: [] });
    await vm.sendMessage();
    expect(vm.draft).toBe('压缩机有异响');
    expect(vm.messages).toHaveLength(0);
    const first = api.send.mock.calls[0][1].client_message_id;
    await vm.sendMessage();
    expect(api.send.mock.calls[1][1].client_message_id).toBe(first);
    expect(vm.messages).toHaveLength(1);
    expect(vm.draft).toBe('');
  });

  it('does not overwrite a new conversation with a late response from the previous one', async () => {
    let resolveOld;
    api.messages.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ items: [{ id: 'new-message', parts: [] }], has_more: false, next_cursor: 1 });
    const old = vm.selectConversation({ id: 'old' });
    await vm.selectConversation({ id: 'new' });
    resolveOld({ items: [{ id: 'old-message', parts: [] }], has_more: false, next_cursor: 1 });
    await old;
    expect(vm.selected.id).toBe('new');
    expect(vm.messages.map(item => item.id)).toEqual(['new-message']);
  });

  it('paginates decimal-string cursors numerically from 9 to 10', async () => {
    api.messages.mockResolvedValueOnce({ items: [{ id: 'one', parts: [] }], next_cursor: '9', has_more: true })
      .mockResolvedValueOnce({ items: [{ id: 'two', parts: [] }], next_cursor: '10', has_more: true })
      .mockResolvedValueOnce({ items: [], next_cursor: '10', has_more: false });
    await vm.selectConversation({ id: 'conversation' });
    expect(vm.error).toBe('');
    expect(api.messages.mock.calls.map(call => call[1])).toEqual([0, '9', '10']);
    expect(vm.messages.map(message => message.id)).toEqual(['one', 'two']);
  });
  it('keeps full 64-bit cursor precision and rejects a non-advancing page', async () => {
    api.messages.mockResolvedValueOnce({ items: [], next_cursor: '9223372036854775806', has_more: true })
      .mockResolvedValueOnce({ items: [], next_cursor: '9223372036854775807', has_more: true })
      .mockResolvedValueOnce({ items: [], next_cursor: '9223372036854775807', has_more: true });
    await vm.selectConversation({ id: 'large' });
    expect(api.messages.mock.calls.map(call => call[1])).toEqual([0, '9223372036854775806', '9223372036854775807']);
    expect(vm.error).toContain('游标异常');
  });
  it('keeps drafts per conversation and avoids sending during Chinese composition', () => {
    vm.selected = { id: 'one' }; vm.draft = '未发送草稿';
    vm.selected = { id: 'two' }; expect(vm.draft).toBe('');
    vm.selected = { id: 'one' }; expect(vm.draft).toBe('未发送草稿');
    const preventDefault = vi.fn();
    vm.composerKeydown({ key: 'Enter', isComposing: true, preventDefault });
    expect(api.send).not.toHaveBeenCalled();
    expect(preventDefault).not.toHaveBeenCalled();
  });
});

describe('owner support lifecycle', () => {
  it('restores handoff from server detail and the latest request event, then cancels and explicitly resumes AI', async () => {
    api.messages.mockResolvedValue({ items: [], next_cursor: '0', has_more: false });
    api.detail.mockResolvedValue({ id: 'c', state: 'HUMAN_QUEUED' });
    api.events.mockResolvedValue({ items: [{ data: { request_id: 'old' } }, { data: { request_id: 'latest' } }], next_cursor: '9223372036854775806', has_more: false });
    support.get.mockResolvedValue({ request_id: 'latest', status: 'queued' });
    await vm.selectConversation({ id: 'c' });
    expect(support.get).toHaveBeenCalledWith('latest'); expect(vm.humanActive).toBe(true);
    api.detail.mockResolvedValue({ id: 'c', state: 'RESOLVED' }); support.get.mockResolvedValue({ request_id: 'latest', status: 'canceled' });
    await vm.cancelHandoff(); expect(support.action).toHaveBeenCalledWith('latest', 'cancel'); expect(vm.selected.state).toBe('RESOLVED');
    vm.draft = '保留的问题'; await vm.sendMessage(); expect(api.send).not.toHaveBeenCalled();
    api.detail.mockResolvedValue({ id: 'c', state: 'AI_ACTIVE' }); await vm.resumeAi(); expect(api.resume).toHaveBeenCalledWith('c'); expect(vm.selected.state).toBe('AI_ACTIVE'); expect(vm.draft).toBe('保留的问题');
  });
  it('reuses the handoff request identifier after a network failure', async () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' };
    api.handoff.mockRejectedValueOnce(new Error('断开')).mockResolvedValueOnce({ request_id: 'r', status: 'queued' });
    support.get.mockResolvedValue({ request_id: 'r', status: 'queued' });
    await vm.handoff(); const id = api.handoff.mock.calls[0][1].client_request_id; await vm.handoff();
    expect(api.handoff.mock.calls[1][1].client_request_id).toBe(id);
  });
  it('polls staff messages into the same owner history and renders their real sender type', async () => {
    api.messages.mockResolvedValueOnce({ items: [{ id: 'user', sender_type: 'user', seq: '9', parts: [] }], next_cursor: '9', has_more: false });
    await vm.selectConversation({ id: 'c' });
    api.messages.mockResolvedValueOnce({ items: [{ id: 'agent', sender_type: 'expert', seq: '10', parts: [{ type: 'text', text: '现场建议' }] }], next_cursor: '10', has_more: false });
    await vm.pollConversation(); expect(api.messages).toHaveBeenLastCalledWith('c', '0', expect.any(AbortSignal)); expect(vm.messages.map(item => item.id)).toEqual(['user', 'agent']); expect(wrapper.text()).toContain('support.expert');
  });
});


describe('AI lifecycle', () => {
  it('deduplicates sequences and resets text on a new attempt', () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' };
    vm.messages = [{ id: 'm', sender_type: 'assistant', parts: [] }];
    const frame = (id, type, attempt, text) => ({ id, type, data: JSON.stringify({ data: { message_id: 'm', attempt, text, chunk_sequence: '1' } }) });
    vm.applyStream(frame('9007199254740993', 'assistant.text.delta', 1, 'old'));
    vm.applyStream(frame('9007199254740993', 'assistant.text.delta', 1, 'duplicate'));
    vm.applyStream(frame('9007199254740994', 'assistant.message.reset', 2));
    vm.applyStream(frame('9007199254740995', 'assistant.text.delta', 2, 'new'));
    expect(vm.messages[0].parts[0].text).toBe('new');
  });
  it('does not allow AI controls while human support is active', async () => {
    vm.selected = { id: 'c', state: 'HUMAN_ACTIVE' };
    await vm.runAction({ run_id: 'r' }, 'cancel');
    expect(api.runAction).not.toHaveBeenCalled();
  });
  it('aborts the previous stream when switching conversations', async () => {
    const controller = new AbortController(); vm.streamController = controller;
    api.messages.mockResolvedValue({ items: [], next_cursor: '0', has_more: false });
    await vm.selectConversation({ id: 'next' });
    expect(controller.signal.aborted).toBe(true);
  });
});

const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const aiFrame = (sequence, type, attempt = '1', text, chunkSequence = '1') => ({
  id: String(sequence), type, data: JSON.stringify({ data: { message_id: 'm', run_id: 'r', attempt, text, chunk_sequence: chunkSequence } }),
});
const assistant = text => ({ id: 'm', sender_type: 'assistant', status: 'streaming', parts: [{ type: 'text', text }] });
const page = items => ({ items, has_more: false, next_cursor: '1' });

describe('AI journal/snapshot races', () => {
  it('replays historical deltas over an already populated snapshot without duplicating output', () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' }; vm.messages = [assistant('hello world')];
    vm.applyStream(aiFrame('1', 'assistant.message.created', '0'));
    vm.applyStream(aiFrame('2', 'ai.run.started'));
    vm.applyStream(aiFrame('3', 'assistant.text.delta', '1', 'hello '));
    vm.applyStream(aiFrame('4', 'assistant.text.delta', '1', 'world', '2'));
    vm.applyStream(aiFrame('5', 'assistant.message.completed'));
    expect(vm.messages[0].parts[0].text).toBe('hello world');
    expect(vm.messages[0].status).toBe('completed');
  });
  it('keeps SSE output when an older polling snapshot arrives after new tokens', async () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' }; vm.messages = [assistant('')];
    vm.applyStream(aiFrame('1', 'assistant.text.delta', '1', 'hello'));
    const snapshot = deferred(); api.messages.mockReturnValueOnce(snapshot.promise);
    const polling = vm.pollConversation();
    vm.applyStream(aiFrame('2', 'assistant.text.delta', '1', ' world', '2'));
    snapshot.resolve(page([assistant('hello')])); await polling;
    expect(vm.messages[0].parts[0].text).toBe('hello world');
  });
  it('does not duplicate future SSE deltas when a polling snapshot is ahead', async () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' }; vm.messages = [assistant('')];
    vm.applyStream(aiFrame('1', 'assistant.text.delta', '1', 'hello'));
    api.messages.mockResolvedValue(page([assistant('hello world')]));
    await vm.pollConversation();
    vm.applyStream(aiFrame('2', 'assistant.text.delta', '1', ' world', '2'));
    expect(vm.messages[0].parts[0].text).toBe('hello world');
  });
  it('polling events recover missed tokens and deduplicate later SSE replay', async () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' }; vm.messages = [assistant('')];
    api.messages.mockResolvedValue(page([assistant('hi')]));
    const frame = aiFrame('9007199254740993', 'assistant.text.delta', '1', 'hi');
    api.events.mockResolvedValue({ items: [{ sequence: frame.id, type: frame.type, ...JSON.parse(frame.data) }], next_cursor: frame.id, has_more: false });
    await vm.pollConversation(); vm.applyStream(frame);
    expect(vm.messages[0].parts[0].text).toBe('hi'); expect(vm.streamCursor).toBe(frame.id);
  });
  it('retains first delta before its message snapshot arrives', () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' };
    vm.applyStream(aiFrame('1', 'assistant.text.delta', '1', 'first'));
    vm.mergeSnapshot([{ ...assistant('first'), seq: '20' }]);
    expect(vm.messages).toHaveLength(1); expect(vm.messages[0].parts[0].text).toBe('first'); expect(vm.messages[0].seq).toBe('20');
  });
  it('fences older attempts and chunk sequence duplicates after reset, including stale snapshots', () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' };
    vm.applyStream(aiFrame('1', 'assistant.text.delta', '1', 'old'));
    vm.applyStream(aiFrame('2', 'assistant.message.reset', '2'));
    vm.applyStream(aiFrame('3', 'assistant.text.delta', '2', 'new'));
    vm.applyStream(aiFrame('4', 'assistant.text.delta', '1', 'late'));
    vm.applyStream(aiFrame('5', 'assistant.text.delta', '2', 'duplicate'));
    vm.mergeSnapshot([assistant('old stale snapshot')]);
    expect(vm.messages[0].parts[0].text).toBe('new'); expect(vm.streamProjections.m.attempt).toBe('2');
  });
});

describe('AI actions and lifecycle isolation', () => {
  it.each(['cancel', 'retry'])('sends only one %s request while the first click is pending', async action => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' }; const done = deferred();
    api.runAction.mockReturnValue(done.promise); api.messages.mockResolvedValue(page([]));
    const run = { run_id: 'r' }; const first = vm.runAction(run, action);
    await vm.runAction(run, action); expect(api.runAction).toHaveBeenCalledTimes(1);
    done.resolve({ run_id: 'r', status: action === 'cancel' ? 'canceled' : 'queued' }); await first;
    expect(vm.runBusy['c:r']).toBeUndefined();
  });
  it('does not append a late send to the newly selected conversation or clear its draft', async () => {
    vm.selected = { id: 'old', state: 'AI_ACTIVE' }; vm.draft = 'old draft';
    const sent = deferred(); api.send.mockReturnValue(sent.promise); const sending = vm.sendMessage();
    api.messages.mockResolvedValue(page([])); await vm.selectConversation({ id: 'new' }); vm.draft = 'new draft';
    sent.resolve({ id: 'old-message', parts: [] }); await sending;
    expect(vm.messages).toEqual([]); expect(vm.draft).toBe('new draft');
    vm.selected = { id: 'old' }; expect(vm.draft).toBe('old draft');
  });
  it('aborts selection requests and releases a blocked stream on unmount', async () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' }; vm.selectionController = new AbortController();
    const selectionSignal = vm.selectionController.signal; let canceled = false;
    api.stream.mockResolvedValue({ status: 200, ok: true, body: new ReadableStream({ cancel() { canceled = true; } }) });
    const streaming = vm.startStream('c', vm.selectionVersion); await vi.waitFor(() => expect(api.stream).toHaveBeenCalledOnce());
    await Promise.resolve(); wrapper.unmount(); await streaming;
    expect(selectionSignal.aborted).toBe(true); expect(canceled).toBe(true);
  });
  it('cancels a late HTTP stream response without reading it after conversation switch', async () => {
    vm.selected = { id: 'old' }; const response = deferred(); api.stream.mockReturnValue(response.promise);
    const streaming = vm.startStream('old', vm.selectionVersion);
    api.messages.mockResolvedValue(page([])); await vm.selectConversation({ id: 'new' });
    let canceled = false;
    response.resolve({ ok: true, status: 200, body: new ReadableStream({ cancel() { canceled = true; } }) });
    await streaming; expect(canceled).toBe(true); expect(vm.messages).toEqual([]);
  });
  it('reconnects after EOF using last processed 64-bit event sequence, and cancels reconnect delay', async () => {
    vi.useFakeTimers(); vm.selected = { id: 'c', state: 'AI_ACTIVE' };
    const frame = aiFrame('9223372036854775806', 'assistant.text.delta', '1', 'answer');
    api.stream.mockImplementationOnce(async () => ({ status: 200, ok: true, body: new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode(`id: ${frame.id}\nevent: ${frame.type}\ndata: ${frame.data}\n\n`)); c.close(); } }) }))
      .mockImplementationOnce(async () => ({ status: 200, ok: true, body: new ReadableStream() }));
    const streaming = vm.startStream('c', vm.selectionVersion);
    await vi.advanceTimersByTimeAsync(1500);
    expect(api.stream.mock.calls[1][1]).toBe('9223372036854775806');
    vm.streamController.abort(); await streaming; expect(api.stream).toHaveBeenCalledTimes(2);
  });
});

describe('AI retry and message ordering', () => {
  it('shows queued retry before next claim increments the attempt', () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' };
    vm.applyStream(aiFrame('1', 'assistant.text.delta', '1', 'partial'));
    vm.applyStream(aiFrame('2', 'ai.run.failed'));
    vm.applyStream(aiFrame('3', 'ai.run.queued'));
    expect(vm.messages[0].status).toBe('queued'); expect(vm.runs[0].status).toBe('queued');
    expect(vm.messages[0].parts[0].text).toBe('partial');
    vm.applyStream(aiFrame('4', 'assistant.message.reset', '2'));
    expect(vm.messages[0].parts).toEqual([]);
  });
  it('places a user send before the assistant placeholder that streamed while send was pending', async () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' }; vm.draft = 'question';
    const sent = deferred(); api.send.mockReturnValue(sent.promise); const sending = vm.sendMessage();
    vm.applyStream(aiFrame('1', 'ai.run.queued', '0'));
    vm.applyStream({ id: '2', type: 'assistant.message.created', data: JSON.stringify({ data: { message_id: 'm', run_id: 'r', attempt: '0', seq: '9007199254740994' } }) });
    sent.resolve({ id: 'u', seq: '9007199254740993', parts: [{ type: 'text', text: 'question' }] }); await sending;
    expect(vm.messages.map(message => message.id)).toEqual(['u', 'm']);
  });
});

describe('B3 mixed message drafts', () => {
  const evidence = (id, kind = 'image', status = 'ready') => ({ kind, clientUploadId: `upload-${id}`, status, file: { name: 'evidence' }, attachment: { id, status } });
  it('sends text and ordered attachments as one message with stable retry identity', async () => {
    vm.selected = { id: 'c', state: 'AI_ACTIVE' }; vm.draft = '现场证据'; vm.attachmentRows = [evidence('9007199254740993', 'video'), evidence('2', 'file')];
    api.send.mockRejectedValueOnce(new Error('断开')).mockResolvedValueOnce({ id: 'm', seq: '1', parts: [] });
    await vm.sendMessage(); const payload = api.send.mock.calls[0][1];
    expect(payload.parts).toEqual([{ type: 'text', text: '现场证据' }, { type: 'video', attachment_id: '9007199254740993' }, { type: 'file', attachment_id: '2' }]);
    expect(vm.draft).toBe('现场证据'); expect(vm.attachmentRows).toHaveLength(2);
    await vm.sendMessage(); expect(api.send.mock.calls[1][1]).toEqual(payload); expect(vm.attachmentRows).toEqual([]); expect(vm.draft).toBe('');
  });
  it('does not send text alone while media is checking or failed', async () => {
    vm.selected = { id: 'c' }; vm.draft = '保留整条消息'; vm.attachmentRows = [evidence('9','image','processing')]; await vm.sendMessage();
    vm.attachmentRows[0].status = 'failed'; await vm.sendMessage(); expect(api.send).not.toHaveBeenCalled(); expect(vm.draft).toBe('保留整条消息');
  });
  it('supports ready attachment-only messages', async () => {
    vm.selected = { id: 'c' }; vm.attachmentRows = [evidence('9')]; api.send.mockResolvedValue({ id: 'm', parts: [] }); await vm.sendMessage();
    expect(api.send.mock.calls[0][1].parts).toEqual([{ type: 'image', attachment_id: '9' }]);
  });
  it('preserves pending idempotency keys across another conversation send', async () => {
    vm.selected = { id: 'old' }; vm.draft = 'old text'; vm.attachmentRows = [evidence('9')]; api.send.mockRejectedValueOnce(new Error('unknown')).mockResolvedValueOnce({ id: 'other', parts: [] }).mockResolvedValueOnce({ id: 'old-message', parts: [] });
    await vm.sendMessage(); const first = api.send.mock.calls[0][1].client_message_id;
    vm.selected = { id: 'new' }; vm.draft = 'other'; await vm.sendMessage(); vm.selected = { id: 'old' }; await vm.sendMessage();
    expect(api.send.mock.calls[2][1].client_message_id).toBe(first); expect(vm.attachmentDrafts.old).toEqual([]);
  });
});

describe('B3 identity isolation', () => {
  it('aborts the old identity list request and rejects its late response', async () => {
    await Promise.resolve(); vm.loadingList = false;
    const pending = deferred(); api.list.mockReturnValueOnce(pending.promise); const loading = vm.loadConversations();
    const signal = api.list.mock.calls.at(-1)[1]; vm.conversations = [{ id: 'old', title: 'private' }]; vm.resetIdentity(); expect(signal.aborted).toBe(true);
    pending.resolve({ items: [{ id: 'old', title: 'private' }], has_more: false, next_cursor: 0 }); await loading;
    expect(vm.conversations).toEqual([]); expect(vm.selected).toBeNull();
  });
  it('rejects a late create response after an identity change', async () => {
    const pending = deferred(); api.create.mockReturnValueOnce(pending.promise); vm.newTitle = 'old account title'; const creating = vm.createConversation();
    const signal = api.create.mock.calls[0][1]; vm.resetIdentity(); expect(signal.aborted).toBe(true);
    pending.resolve({ id: 'private', title: 'old account title' }); await creating; expect(vm.selected).toBeNull(); expect(vm.creating).toBe(false);
  });
});

describe('B4 annotation reply integration', () => {
  const generated = { kind:'image',clientUploadId:'annotation-12',status:'ready',progress:100,attachment:{id:'13',conversation_id:'c',filename:'服务端标注.jpg',size:123,status:'ready',metadata:{annotation_id:'12',frame_id:'10'}} };
  it('stages server media without a local File and preserves text plus annotation with the original send key on retry', async () => {
    vm.selected={id:'c',state:'AI_ACTIVE'}; vm.draft='请检查圈选位置';vm.stageAnnotation({scopeKey:vm.attachmentScope,row:generated});await wrapper.vm.$nextTick();expect(wrapper.text()).toContain('服务端标注.jpg');
    api.send.mockRejectedValueOnce(new Error('disconnect')).mockResolvedValueOnce({id:'m',parts:[]});await vm.sendMessage();const first=api.send.mock.calls[0][1];expect(first.parts).toEqual([{type:'text',text:'请检查圈选位置'},{type:'image',attachment_id:'13'}]);expect(vm.attachmentRows).toHaveLength(1);
    await vm.sendMessage();expect(api.send.mock.calls[1][1]).toEqual(first);expect(vm.attachmentRows).toHaveLength(0);
  });
  it('rejects staging into a different conversation or a resolved conversation', () => {
    vm.selected={id:'c',state:'AI_ACTIVE'};vm.stageAnnotation({scopeKey:'previous-conversation',row:generated});expect(vm.attachmentRows).toEqual([]);
    vm.selected={id:'c',state:'RESOLVED'};vm.stageAnnotation({scopeKey:vm.attachmentScope,row:generated});expect(vm.attachmentRows).toEqual([]);
  });
  it('treats sending as a temporary annotation pause while a successful send keeps write permission', async () => {
    vm.selected={id:'one',state:'AI_ACTIVE'};vm.draft='附上标注';vm.attachmentRows=[{clientUploadId:'render',kind:'image',status:'ready',attachment:{id:'13',status:'ready'}}];await vm.$nextTick();const editor=wrapper.findComponent({name:'AnnotationEditor'});
    let finish;api.send.mockReturnValueOnce(new Promise(resolve=>{finish=resolve;}));const sending=vm.sendMessage();await vm.$nextTick();expect(editor.props('writable')).toBe(true);expect(editor.props('disabled')).toBe(true);expect(editor.vm.error).toBe('');
    finish({id:'saved',parts:[{type:'text',text:'附上标注'},{type:'image',attachment_id:'13'}]});await sending;await vm.$nextTick();expect(vm.draft).toBe('');expect(vm.attachmentRows).toEqual([]);expect(editor.props('writable')).toBe(true);expect(editor.props('disabled')).toBe(false);expect(editor.vm.error).toBe('');
    vm.selected.state='RESOLVED';await vm.$nextTick();expect(editor.props('writable')).toBe(false);expect(editor.vm.error).toBe('annotations.denied');
  });

  it('sends text through the existing save hook during realtime and retains all attachment drafts', async () => {
    vm.selected={id:'one',state:'AI_ACTIVE'};await vm.$nextTick();vm.realtimeState={session:{id:'s',status:'active'},injections:[]};vm.draft='实时补充文字';const row={clientUploadId:'keep',kind:'image',status:'failed',attachment:{id:'9',status:'failed'}};vm.attachmentRows=[row];api.send.mockResolvedValue({id:'saved',parts:[{type:'text',text:'实时补充文字'}]});await vm.sendMessage();expect(api.send.mock.calls[0][1].parts).toEqual([{type:'text',text:'实时补充文字'}]);expect(vm.draft).toBe('');expect(vm.attachmentRows).toEqual([row]);
  });
  it('shows audio transcription placeholders and actionable long-audio failure without an endless retry button', async () => {
    vm.selected={id:'one',state:'AI_ACTIVE'};vm.messages=[{id:'user-audio',sender_type:'user',status:'transcribing',parts:[]},{id:'failed',sender_type:'assistant',status:'failed',parts:[]}];vm.runs=[{run_id:'run',output_message_id:'failed',status:'failed',error_code:'OMNI_AUDIO_LIMIT'}];await vm.$nextTick();expect(wrapper.text()).toContain('realtime.input_transcribing');expect(wrapper.text()).toContain('ai.omniAudioLimit');expect(wrapper.text()).not.toContain('ai.retry');vm.messages[0].status='failed';await vm.$nextTick();expect(wrapper.text()).toContain('realtime.input_failed');
  });
  it('keeps saved text distinct from provider synchronization and ignores a human room from a previous conversation', async () => {
    vm.selected={id:'one',state:'AI_ACTIVE'};await vm.$nextTick();vm.messages=[{id:'saved',sender_type:'user',parts:[{type:'text',text:'already saved'}]}];vm.realtimeState={session:{id:'s',status:'active'},injections:[{id:'i',message_id:'saved',status:'queued'}]};await vm.$nextTick();expect(wrapper.text()).toContain('已保存');expect(wrapper.text()).toContain('realtime.injection_queued');vm.humanMediaChanged({id:'human',conversation_id:'other',status:'active'});expect(vm.humanMedia).toBeNull();vm.humanMediaChanged({id:'human',conversation_id:'one',status:'invited'});expect(vm.realtimeAllowed).toBe(false);
  });

  it('retries an uncertain realtime text save with the same message ID after realtime ends, without adding attachment drafts', async () => {
    vm.selected={id:'one',state:'AI_ACTIVE'};await vm.$nextTick();vm.realtimeState={session:{id:'s',status:'active'},injections:[]};vm.draft='同一条文字';const row={clientUploadId:'retained',kind:'image',status:'failed',attachment:{id:'9',status:'failed'}};vm.attachmentRows=[row];api.send.mockRejectedValueOnce(new Error('uncertain'));await vm.sendMessage();const first=api.send.mock.calls[0][1];vm.realtimeState={session:{id:'s',status:'ended'},injections:[]};api.send.mockResolvedValueOnce({id:'saved',parts:first.parts});await vm.sendMessage();expect(api.send.mock.calls[1][1]).toEqual(first);expect(vm.attachmentRows).toEqual([row]);expect(vm.draft).toBe('');
  });

});

describe('unified conversation sources', () => {
  it('switches to a native hardware history without sending it to App write routes and preserves the App draft', async () => {
    await Promise.resolve(); await Promise.resolve();
    const app = { id: 'same-id', title: 'App conversation', kind: 'conversation', row_key: 'conversation:same-id', source: 'app' };
    const legacy = { id: 'same-id', title: 'Device history', kind: 'device_history', row_key: 'history:agent-a:same-id', source: 'hardware', agent_id: 'agent-a', agent_name: 'Agent A' };
    vm.selected = app; vm.draft = 'Saved App draft'; vm.deviceFilter = 'mac-a';
    vm.selectIndexItem(legacy); await wrapper.vm.$nextTick();
    expect(vm.selected).toBeNull(); expect(vm.legacySelected.id).toBe('same-id'); expect(vm.indexSelected(app)).toBe(false); expect(vm.indexSelected(legacy)).toBe(true);
    const history = wrapper.findComponent({ name: 'AgentChatHistory' }); expect(history.props('historyAgentId')).toBe('agent-a'); expect(history.props('historyDevice')).toBe('mac-a');
    api.messages.mockResolvedValue({ items: [], next_cursor: '0', has_more: false });
    await vm.selectConversation(app); expect(vm.draft).toBe('Saved App draft'); expect(vm.legacySelected).toBeNull(); expect(api.send).not.toHaveBeenCalled();
  });
  it('rejects a late source page after switching filters, even when the transport ignores abort', async () => {
    await new Promise(resolve => setTimeout(resolve, 0));
    let old; api.index.mockImplementationOnce(() => new Promise(resolve => old = resolve)).mockResolvedValueOnce({ items: [], next_cursor: '', has_more: false });
    const pending = vm.loadConversations(); const oldSignal = api.index.mock.calls.at(-1)[1];
    vm.changeSource('hardware'); await new Promise(resolve => setTimeout(resolve, 0));
    old({ items: [{ id: 'old-app', title: 'Wrong source' }], next_cursor: 'old-cursor', has_more: true }); await pending;
    expect(oldSignal.aborted).toBe(true); expect(vm.sourceFilter).toBe('hardware'); expect(vm.conversations).toEqual([]); expect(vm.listHasMore).toBe(false);
  });
  it('sends device and agent filters to the server rather than filtering the current loaded page', async () => {
    await new Promise(resolve => setTimeout(resolve, 0)); api.index.mockResolvedValue({ items: [], next_cursor: '', has_more: false });
    vm.changeSource('hardware'); await new Promise(resolve => setTimeout(resolve, 0));
    vm.changeHardwareFilter('agent-a', 'mac-a'); await new Promise(resolve => setTimeout(resolve, 0));
    expect(api.index).toHaveBeenLastCalledWith({ before: '', limit: 50, source: 'hardware', agent_id: 'agent-a', mac_address: 'mac-a' }, expect.any(AbortSignal));
    vm.changeSource('app'); expect(vm.agentFilter).toBe(''); expect(vm.deviceFilter).toBe('');
  });
  it('does not expose the App composer or retry writes for canonical device-owned conversations', async () => {
    api.messages.mockResolvedValue({ items: [], next_cursor: '0', has_more: false });
    await vm.selectConversation({ id: 'hardware', title: 'Hardware', source: 'hardware', state: 'AI_ACTIVE' });
    vm.draft = 'must stay local'; await vm.sendMessage();
    expect(wrapper.find('.composer-area').exists()).toBe(false); expect(wrapper.find('.hardware-readonly-note').exists()).toBe(true); expect(api.send).not.toHaveBeenCalled();
  });
  it('requires ending an active call before changing source', async () => {
    vm.realtimeState = { session: { id: 'live', status: 'active' }, status: 'active' };
    vm.changeSource('hardware'); await wrapper.vm.$nextTick();
    expect(vm.sourceFilter).toBe('all'); expect(wrapper.find('#source-tab-all').attributes('disabled')).toBeDefined();
  });
});
it('exposes four source tabs and asks the server separately for App and web', async () => {
  await new Promise(resolve => setTimeout(resolve, 0));
  expect(wrapper.findAll('[role="tab"]')).toHaveLength(4);
  expect(wrapper.find('#source-tab-all').attributes('aria-selected')).toBe('true');
  await wrapper.find('#source-tab-app').trigger('click'); await new Promise(resolve => setTimeout(resolve, 0));
  expect(api.index).toHaveBeenLastCalledWith(expect.objectContaining({ source: 'app' }), expect.any(AbortSignal));
  expect(wrapper.find('#source-tab-app').attributes('aria-selected')).toBe('true');
  expect(wrapper.find('.new-conversation').exists()).toBe(false);
  await wrapper.find('#source-tab-web').trigger('click'); await new Promise(resolve => setTimeout(resolve, 0));
  expect(api.index).toHaveBeenLastCalledWith(expect.objectContaining({ source: 'web' }), expect.any(AbortSignal));
  expect(wrapper.find('#source-tab-web').attributes('aria-selected')).toBe('true');
  expect(wrapper.find('.new-conversation').exists()).toBe(true);
});
it('supports arrow keys and Home/End on the source tabs', async () => {
  await wrapper.find('#source-tab-all').trigger('keydown', { key: 'ArrowRight' });
  expect(vm.sourceFilter).toBe('app');
  await wrapper.find('#source-tab-app').trigger('keydown', { key: 'ArrowRight' });
  expect(vm.sourceFilter).toBe('web');
  await wrapper.find('#source-tab-web').trigger('keydown', { key: 'End' });
  expect(vm.sourceFilter).toBe('hardware');
  await wrapper.find('#source-tab-hardware').trigger('keydown', { key: 'Home' });
  expect(vm.sourceFilter).toBe('all');
});

it('locks the device desk to hardware and never offers or sends an owner message', async () => {
  wrapper.unmount();
  wrapper=mount(Workspace,{props:{hardwareOnly:true},global:{mocks:{$t:key=>key,$route:{query:{source:'web'}}},stubs:{AgentChatHistory:true,LegacyIcon:true,ElDialog:true,ElInput:true,ElButton:true}}}); vm=wrapper.vm;
  await Promise.resolve(); await Promise.resolve();
  expect(vm.sourceFilter).toBe('hardware'); vm.changeSource('web'); expect(vm.sourceFilter).toBe('hardware');
  vm.openCreate(); expect(vm.createVisible).toBe(false); expect(wrapper.find('.composer-area textarea').exists()).toBe(false);
  expect(wrapper.find('.device-debug-tool textarea').attributes('disabled')).toBeDefined();
  await vm.selectIndexItem({id:'web',source:'web'}); expect(vm.selected).toBeNull();
  vm.selected={id:'hw',source:'hardware',state:'AI_ACTIVE'}; vm.draft='禁止'; await vm.sendMessage(); expect(api.send).not.toHaveBeenCalled();
  vm.newTitle='禁止创建'; await vm.createConversation(); expect(api.create).not.toHaveBeenCalled();
  expect(api.index.mock.calls.at(-1)[0].source).toBe('hardware');
});

describe('hardware message history windows', () => {
  const item = { id: 'hardware', title: 'Device', source: 'hardware', state: 'AI_ACTIVE' };
  function ledger(last = 35n) {
    api.detail.mockResolvedValue({ last_seq: String(last), state: 'AI_ACTIVE' });
    api.messages.mockImplementation(async (id, after, signal, limit = 100) => {
      const start = BigInt(after) + 1n, items = [];
      for (let seq = start; seq <= last && items.length < limit; seq++) items.push({ id: `${id}-${seq}`, seq: String(seq), parts: [{ type: 'text', text: `message ${seq}` }] });
      const next = items.at(-1)?.seq || String(after);
      return { items, next_cursor: next, has_more: BigInt(next) < last };
    });
  }
  function scrollingList() {
    const list = wrapper.find('.message-list').element;
    Object.defineProperties(list, {
      scrollHeight: { configurable: true, get: () => vm.messages.length * 100 },
      clientHeight: { configurable: true, value: 300 },
    });
    return list;
  }
  it('reads only the latest ten rows and opens at the bottom without replaying the entire event journal', async () => {
    ledger(); const list = scrollingList();
    await vm.selectConversation(item); await vm.$nextTick();
    expect(api.messages).toHaveBeenCalledExactlyOnceWith('hardware', '25', expect.any(AbortSignal), 10);
    expect(vm.messages.map(message => message.seq)).toEqual(Array.from({length: 10}, (_, i) => String(i + 26)));
    expect(list.scrollTop).toBe(list.scrollHeight);
    expect(vm.messageHasMore).toBe(true);
    expect(api.events).not.toHaveBeenCalled(); expect(api.runs).not.toHaveBeenCalled(); expect(api.stream).not.toHaveBeenCalled();
  });
  it('prepends ten earlier rows on upward scrolling and keeps the previous content at the same viewport position', async () => {
    ledger(); const list = scrollingList(); await vm.selectConversation(item); await vm.$nextTick();
    list.scrollTop = 30; await wrapper.find('.message-list').trigger('scroll'); await flushPromises();
    expect(vm.messages).toHaveLength(20); expect(vm.messages[0].seq).toBe('16');
    expect(api.messages).toHaveBeenLastCalledWith('hardware', '15', expect.any(AbortSignal), 10);
    expect(list.scrollTop).toBe(1030);
  });
  it('does not load history while scrolling down or when initial positioning emits a scroll event', async () => {
    ledger(); const list = scrollingList(); await vm.selectConversation(item); await vm.$nextTick();
    await wrapper.find('.message-list').trigger('scroll');
    vm.lastMessageScrollTop = 0; list.scrollTop = 30; await wrapper.find('.message-list').trigger('scroll');
    expect(api.messages).toHaveBeenCalledTimes(1);
  });
  it('reaches the oldest message once, without duplicates or another request after the last page', async () => {
    ledger(25n); await vm.selectConversation(item);
    await vm.loadOlderMessages(); await vm.loadOlderMessages(); await vm.loadOlderMessages();
    expect(vm.messages).toHaveLength(25); expect(new Set(vm.messages.map(message => message.id)).size).toBe(25);
    expect(vm.messages[0].seq).toBe('1'); expect(vm.messageHasMore).toBe(false);
    expect(api.messages).toHaveBeenCalledTimes(3);
  });
  it('keeps an empty or short conversation from requesting older records', async () => {
    ledger(3n); await vm.selectConversation(item); expect(vm.messages).toHaveLength(3); expect(vm.messageHasMore).toBe(false);
    ledger(0n); await vm.selectConversation(item); expect(vm.messages).toEqual([]); expect(vm.messageHasMore).toBe(false);
  });
  it('preserves 64-bit sequence precision when locating the latest and earlier pages', async () => {
    ledger(9007199254741005n); await vm.selectConversation(item); await vm.loadOlderMessages();
    expect(api.messages.mock.calls.map(call => call[1])).toEqual(['9007199254740995', '9007199254740985']);
    expect(vm.messages).toHaveLength(20); expect(vm.messages.at(-1).seq).toBe('9007199254741005');
  });
  it('prevents duplicate loads and discards a late history response after switching conversations', async () => {
    ledger(); await vm.selectConversation(item);
    const pending = deferred(); api.messages.mockReturnValueOnce(pending.promise);
    const loading = vm.loadOlderMessages(); const signal = api.messages.mock.calls.at(-1)[2];
    await vm.loadOlderMessages(); expect(api.messages).toHaveBeenCalledTimes(2);
    await vm.selectConversation({ ...item, id: 'new-hardware' });
    expect(signal.aborted).toBe(true);
    pending.resolve({ items: [{ id: 'old-row', seq: '1', parts: [] }], next_cursor: '1', has_more: true }); await loading;
    expect(vm.selected.id).toBe('new-hardware'); expect(vm.messages.every(row => row.id.startsWith('new-hardware-'))).toBe(true);
    expect(vm.loadingOlderMessages).toBe(false);
  });
  it('keeps loaded rows on failure and retries the same history window explicitly', async () => {
    ledger(); await vm.selectConversation(item);
    api.messages.mockRejectedValueOnce(new Error('offline'));
    await vm.loadOlderMessages(); expect(vm.historyError).toBe(true); expect(vm.messages).toHaveLength(10);
    expect(wrapper.find('.message-history-status button').text()).toBe('chatHistory.earlierFailed');
    await wrapper.find('.message-history-status button').trigger('click'); await flushPromises();
    expect(vm.historyError).toBe(false); expect(vm.messages).toHaveLength(20);
    expect(api.messages.mock.calls.slice(1).map(call => call[1])).toEqual(['15', '15']);
  });
  it('polls recent and new rows without loading older history or pulling a history reader to the bottom', async () => {
    ledger(); const list = scrollingList(); await vm.selectConversation(item); await vm.$nextTick();
    list.scrollTop = 100; ledger(36n); await vm.pollConversation(); await vm.$nextTick();
    expect(api.messages).toHaveBeenLastCalledWith('hardware', '25', expect.any(AbortSignal));
    expect(vm.messages).toHaveLength(11); expect(vm.messages[0].seq).toBe('26'); expect(vm.messages.at(-1).seq).toBe('36');
    expect(list.scrollTop).toBe(100); expect(api.events).not.toHaveBeenCalled();
    list.scrollTop = list.scrollHeight - list.clientHeight; ledger(37n); await vm.pollConversation(); await vm.$nextTick();
    expect(list.scrollTop).toBe(list.scrollHeight); expect(vm.messages.at(-1).seq).toBe('37');
  });
});

describe('find historical hardware messages', () => {
  const item = { id: 'device', source: 'hardware', title: 'Device history' };
  beforeEach(() => {
    api.detail.mockResolvedValue({ last_seq: '100' });
    api.messages.mockImplementation(async (id, after, signal, limit = 100) => {
      const items = Array.from({length: 100}, (_, index) => ({ id: id + '-' + (index + 1), seq: String(index + 1), parts: [{ type: 'text', text: 'message ' + (index + 1) }] })).filter(row => BigInt(row.seq) > BigInt(after)).slice(0, limit);
      return { items, next_cursor: items.at(-1)?.seq || String(after), has_more: BigInt(items.at(-1)?.seq || after) < 100n };
    });
  });
  it('opens a separately scoped full-history finder without expanding the ten-row main conversation', async () => {
    await vm.selectConversation(item);
    await wrapper.find('.history-search-entry').trigger('click');
    const drawer = wrapper.findComponent({name:'HistorySearchDrawer'});
    expect(drawer.exists()).toBe(true); expect(drawer.props('scopeKey')).toBe(vm.attachmentScope);
    const page = await drawer.props('loadPage')('', new AbortController().signal);
    expect(page.items).toHaveLength(100); expect(page.items[0].seq).toBe('100'); expect(vm.messages).toHaveLength(10);
  });
  it('locates a result in a compact context window and pauses live polling until returning to latest', async () => {
    await vm.selectConversation(item); vm.historyVisible = true;
    await vm.locateHistoryMessage({ id: 'device-20', seq: '20' }); await vm.$nextTick();
    expect(vm.messages.map(row => row.seq)).toEqual(Array.from({length: 10}, (_, i) => String(i + 16)));
    expect(wrapper.find('.history-target').attributes('data-message-id')).toBe('device-20');
    expect(vm.historyVisible).toBe(false); expect(vm.historyContextActive).toBe(true);
    const count = api.messages.mock.calls.length; await vm.pollConversation(); expect(api.messages).toHaveBeenCalledTimes(count);
    await vm.loadOlderMessages(); expect(vm.messages[0].seq).toBe('6'); expect(vm.messages.at(-1).seq).toBe('25');
    await wrapper.find('.history-context-bar button').trigger('click'); await flushPromises();
    expect(vm.messages).toHaveLength(10); expect(vm.messages[0].seq).toBe('91'); expect(vm.historyContextActive).toBe(false); expect(wrapper.find('.history-target').exists()).toBe(false);
  });
  it('cannot replace a different conversation with a late result-context request', async () => {
    await vm.selectConversation(item);
    const pending = deferred(); api.messages.mockReturnValueOnce(pending.promise);
    const locating = vm.locateHistoryMessage({ id: 'device-20', seq: '20' });
    await vm.selectConversation({ ...item, id: 'other' });
    pending.resolve({ items: [{ id: 'device-20', seq: '20', parts: [] }], next_cursor: '20', has_more: true }); await locating;
    expect(vm.selected.id).toBe('other'); expect(vm.messages.every(row => row.id.startsWith('other-'))).toBe(true); expect(vm.historyContextActive).toBe(false);
  });
  it('keeps the current messages and finder open when the requested message is no longer available', async () => {
    await vm.selectConversation(item); vm.historyVisible = true;
    await vm.locateHistoryMessage({ id: 'missing', seq: '20' });
    expect(vm.messages[0].seq).toBe('91'); expect(vm.historyVisible).toBe(true); expect(vm.historyContextActive).toBe(false); expect(vm.error).toBe('historyFinder.jumpFailed');
  });
});

it('fences pending polling and older-history responses when a result opens a different message window', async () => {
  api.detail.mockResolvedValue({ last_seq: '100' });
  api.messages.mockResolvedValue({ items: [{ id: 'recent', seq: '100', parts: [] }], next_cursor: '100', has_more: false });
  await vm.selectConversation({ id: 'device', source: 'hardware' });
  const poll = deferred(), history = deferred();
  api.messages.mockReturnValueOnce(poll.promise).mockReturnValueOnce(history.promise).mockResolvedValueOnce({ items: [{ id: 'target', seq: '20', parts: [] }], next_cursor: '20', has_more: true });
  const polling = vm.pollConversation(), loadingHistory = vm.loadOlderMessages();
  const oldSignal = api.messages.mock.calls.at(-1)[2];
  await vm.locateHistoryMessage({ id: 'target', seq: '20' }); expect(oldSignal.aborted).toBe(true);
  poll.resolve({ items: [{ id: 'late-recent', seq: '101', parts: [] }], next_cursor: '101', has_more: false });
  history.resolve({ items: [{ id: 'late-history', seq: '80', parts: [] }], next_cursor: '80', has_more: true });
  await Promise.all([polling, loadingHistory]);
  expect(vm.messages.map(row => row.id)).toEqual(['target']); expect(vm.messageBefore).toBe('16'); expect(vm.loadingOlderMessages).toBe(false); expect(vm.pollBusy).toBe(false);
});

it('uses the shared collaboration layout for hardware and opens its tool card without expanding the recent window', async () => {
  await wrapper.setProps({ hardwareOnly: true });
  api.detail.mockResolvedValue({ last_seq: '10' });
  api.messages.mockResolvedValue({ items: Array.from({ length: 10 }, (_, index) => ({ id: 'm' + index, seq: String(index + 1), parts: [] })), next_cursor: '10', has_more: false });
  await vm.selectConversation({ id: 'hardware', source: 'hardware', title: 'Device' });
  const layout = wrapper.findComponent({ name: 'ExpertWorkspaceLayout' });
  expect(layout.exists()).toBe(true);
  expect(wrapper.find('.panel-heading .history-search-entry').exists()).toBe(false);
  expect(wrapper.findAll('.history-search-entry')).toHaveLength(1);
  await layout.findAll('.mobile-panels button')[1].trigger('click');
  expect(layout.vm.toolsOpen).toBe(true);
  await wrapper.find('[data-workspace-region="tools"] .history-search-entry').trigger('click');
  expect(layout.vm.toolsOpen).toBe(false);
  expect(wrapper.findComponent({ name: 'HistorySearchDrawer' }).exists()).toBe(true);
  expect(vm.messages).toHaveLength(10);
  vm.clearSourceSelection(); await vm.$nextTick();
  expect(wrapper.find('[data-workspace-region="tools"] .history-search-entry').attributes('disabled')).toBeDefined();
  expect(wrapper.findComponent({ name: 'HistorySearchDrawer' }).exists()).toBe(false);
});

it('routes the same tool card to native device history and disables it until that session is ready', async () => {
  wrapper.unmount();
  const open = vi.fn();
  wrapper = mount(Workspace, { props: { hardwareOnly: true }, global: { mocks: { $t: key => key }, stubs: {
    HistorySearchDrawer: true, LegacyIcon: true, ElDialog: true,
    AgentChatHistory: { name: 'AgentChatHistory', emits: ['history-availability'], methods: { openHistorySearch: open }, template: '<div>native history</div>' },
  } } }); vm = wrapper.vm;
  await flushPromises();
  vm.selectIndexItem({ id: 'native', row_key: 'native-row', agent_id: 'agent', source: 'hardware', kind: 'device_history' }); await vm.$nextTick();
  const button = wrapper.find('[data-workspace-region="tools"] .history-search-entry');
  expect(button.attributes('disabled')).toBeDefined();
  wrapper.findComponent({ name: 'AgentChatHistory' }).vm.$emit('history-availability', true); await vm.$nextTick();
  await button.trigger('click'); expect(open).toHaveBeenCalledTimes(1); expect(vm.historyVisible).toBe(false);
  vm.clearSourceSelection(); await vm.$nextTick(); expect(button.attributes('disabled')).toBeDefined();
});
