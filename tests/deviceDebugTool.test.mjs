import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, afterEach, it, expect, vi } from 'vitest';
import Tool from '../src/components/DeviceDebugTool.vue';
let wrapper, api;
const target = { available: true, deviceId: 'device-a', conversationId: 'conversation-a' };
const render = () => wrapper = mount(Tool, { props: { agentId: 'agent', macAddress: 'aa:bb', token: 'owner', identity: 'identity-a', client: api }, global: { mocks: { $t: key => key } } });
beforeEach(() => { vi.useFakeTimers(); api = { target: vi.fn().mockResolvedValue(target), submit: vi.fn().mockResolvedValue({ id: 'command', state: 'pending' }), command: vi.fn().mockResolvedValue({ id: 'command', state: 'succeeded' }) }; });
afterEach(() => { wrapper?.unmount(); vi.useRealTimers(); });
it('only reads target status on entry and disables offline or unsupported input', async () => {
  render(); await flushPromises(); expect(api.submit).not.toHaveBeenCalled(); expect(wrapper.find('textarea').attributes('disabled')).toBeUndefined();
  api.target.mockResolvedValue({ available: false, reason: 'device_offline' }); await wrapper.find('.refresh-target').trigger('click'); await flushPromises();
  expect(wrapper.find('textarea').attributes('disabled')).toBeDefined(); expect(wrapper.text()).toContain('deviceDebug.offline');
  api.target.mockRejectedValue(Object.assign(new Error('missing'), { status: 404 })); await wrapper.find('.refresh-target').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('deviceDebug.unsupported'); expect(api.submit).not.toHaveBeenCalled();
  api.target.mockRejectedValue(Object.assign(new Error('missing device'), { status: 404, code: 'device_unavailable' })); await wrapper.find('.refresh-target').trigger('click'); await flushPromises();
  expect(wrapper.text()).toContain('deviceDebug.unavailable');
});
it('keeps a stable retry key after an ambiguous POST failure, then follows receipt and clears only the sent draft', async () => {
  api.submit.mockRejectedValueOnce(new TypeError('network')).mockResolvedValueOnce({ id: 'command', state: 'pending' });
  render(); await flushPromises(); await wrapper.find('textarea').setValue('现在几点？'); await wrapper.find('form').trigger('submit'); await flushPromises();
  const first = api.submit.mock.calls[0][1]; expect(wrapper.find('textarea').element.value).toBe('现在几点？');
  await wrapper.find('form').trigger('submit'); await flushPromises(); expect(api.submit.mock.calls[1][1]).toEqual(first);
  expect(wrapper.find('textarea').attributes('disabled')).toBeDefined(); await vi.advanceTimersByTimeAsync(1000); await flushPromises();
  expect(wrapper.find('textarea').element.value).toBe(''); expect(wrapper.text()).toContain('deviceDebug.succeeded');
  expect(wrapper.emitted('submitted')[0][0]).toMatchObject({ conversationId: 'conversation-a', deviceId: 'device-a' });
});
it('fences an old device and identity without submitting or exposing late results', async () => {
  let finish; api.submit.mockReturnValue(new Promise(resolve => finish = resolve));
  render(); await flushPromises(); await wrapper.find('textarea').setValue('旧设备输入'); await wrapper.find('form').trigger('submit'); await flushPromises();
  const signal = api.submit.mock.calls[0][2]; await wrapper.setProps({ macAddress: 'cc:dd', identity: 'new-identity', token: 'new-owner' }); await flushPromises();
  expect(signal.aborted).toBe(true); expect(wrapper.find('textarea').element.value).toBe('');
  finish({ id: 'old-command', state: 'succeeded' }); await flushPromises(); expect(wrapper.emitted('submitted')).toBeUndefined();
  expect(wrapper.text()).not.toContain('deviceDebug.succeeded');
});
it('keeps receipt routing bound to the submitted conversation when connection metadata refreshes', async () => {
  render(); await flushPromises(); await wrapper.find('textarea').setValue('测试'); await wrapper.find('form').trigger('submit'); await flushPromises();
  api.target.mockResolvedValue({ ...target, conversationId: 'new-session' }); await wrapper.find('.refresh-target').trigger('click'); await flushPromises();
  await vi.advanceTimersByTimeAsync(1000); await flushPromises(); expect(wrapper.emitted('submitted')[0][0].conversationId).toBe('conversation-a');
});
it('bounds polling and does not automatically resubmit an unconfirmed command', async () => {
  api.command.mockResolvedValue({ id: 'command', state: 'dispatched' });
  render(); await flushPromises(); await wrapper.find('textarea').setValue('测试'); await wrapper.find('form').trigger('submit'); await flushPromises();
  await vi.advanceTimersByTimeAsync(41000); await flushPromises();
  expect(wrapper.text()).toContain('deviceDebug.unknown'); expect(api.submit).toHaveBeenCalledTimes(1);
  const count = api.command.mock.calls.length; await vi.advanceTimersByTimeAsync(5000); expect(api.command).toHaveBeenCalledTimes(count);
});
it('requires explicit send and leaves Enter and IME composition as editing gestures', async () => {
  render(); await flushPromises(); const textarea = wrapper.find('textarea'); await textarea.setValue('测试');
  await textarea.trigger('keydown', { key: 'Enter' }); await textarea.trigger('keydown', { key: 'Enter', ctrlKey: true, isComposing: true }); expect(api.submit).not.toHaveBeenCalled();
  await textarea.trigger('keydown', { key: 'Enter', ctrlKey: true }); await flushPromises(); expect(api.submit).toHaveBeenCalledTimes(1);
});
