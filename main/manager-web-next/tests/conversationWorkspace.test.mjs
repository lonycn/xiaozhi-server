import { mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Workspace from '../src/views/ConversationWorkspace.vue';
const api = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn(), messages: vi.fn(), send: vi.fn(), detail: vi.fn(), events: vi.fn(), handoff: vi.fn(), resume: vi.fn(), runs: vi.fn(), stream: vi.fn(), runAction: vi.fn() }));
vi.mock('../src/apis/module/conversation', () => ({ default: api }));
const support = vi.hoisted(() => ({ get: vi.fn(), action: vi.fn() }));
vi.mock('../src/apis/module/support', () => ({ default: support }));
let wrapper, vm;
beforeEach(() => {
  vi.resetAllMocks();
  api.runs.mockRejectedValue(Object.assign(new Error('disabled'), { status: 404 }));
  api.list.mockResolvedValue({ items: [], has_more: false, next_cursor: 0 });
  api.detail.mockResolvedValue({ state: 'AI_ACTIVE' });
  api.events.mockResolvedValue({ items: [], next_cursor: '0', has_more: false });
  wrapper = mount(Workspace, { global: { mocks: { $t: key => key }, stubs: { LegacyIcon: true, RouterLink: true, ElDialog: true, ElInput: true, ElButton: true } } });
  vm = wrapper.vm;
});
afterEach(() => { if (wrapper.exists()) wrapper.unmount(); vi.useRealTimers(); });

describe('conversation workspace', () => {
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

});
