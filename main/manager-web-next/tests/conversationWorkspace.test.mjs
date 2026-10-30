import { mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Workspace from '../src/views/ConversationWorkspace.vue';
const api = vi.hoisted(() => ({ list: vi.fn(), create: vi.fn(), messages: vi.fn(), send: vi.fn() }));
vi.mock('../src/apis/module/conversation', () => ({ default: api }));
let wrapper, vm;
beforeEach(() => {
  vi.resetAllMocks();
  api.list.mockResolvedValue({ items: [], has_more: false, next_cursor: 0 });
  wrapper = mount(Workspace, { global: { stubs: { LegacyIcon: true, RouterLink: true, ElDialog: true, ElInput: true, ElButton: true } } });
  vm = wrapper.vm;
});
afterEach(() => wrapper.unmount());

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
