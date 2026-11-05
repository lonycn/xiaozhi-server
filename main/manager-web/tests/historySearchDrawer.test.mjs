import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
import Drawer from '../src/components/HistorySearchDrawer.vue';

let wrapper;
const row = (id, parts = [{ type: 'text', text: '消息 ' + id }], created_at = '2026-11-03T16:00:00Z') => ({ id: String(id), seq: String(id), sender_type: 'user', parts, created_at });
const page = (items, next_before = '1', has_more = false) => ({ items, next_before, has_more });
const waitFilter = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await flushPromises(); };
function render(loadPage, scopeKey = 'account:conversation') {
  wrapper = mount(Drawer, { props: { title: 'Device conversation', scopeKey, loadPage }, global: {
    mocks: { $t: (key, values) => key + (values ? ':' + values.count : '') },
    stubs: {
      ElDrawer: { props: ['modelValue'], emits: ['update:modelValue'], template: '<section role="dialog"><button class="drawer-close" @click="$emit(\'update:modelValue\',false)">close</button><slot /></section>' },
      AttachmentPart: { name: 'AttachmentPart', props: ['part', 'scopeKey'], template: '<div class="private-part">{{ part.type }}</div>' },
    },
  } });
  return wrapper;
}
afterEach(() => { wrapper?.unmount(); vi.useRealTimers(); vi.restoreAllMocks(); });

it('opens with twenty results and reveals cached records without fetching again', async () => {
  const load = vi.fn().mockResolvedValue(page(Array.from({ length: 60 }, (_, index) => row(60 - index))));
  render(load); await flushPromises();
  expect(wrapper.findAll('.finder-record')).toHaveLength(20); expect(load).toHaveBeenCalledTimes(1);
  await wrapper.find('.finder-more').trigger('click'); await flushPromises();
  expect(wrapper.findAll('.finder-record')).toHaveLength(40); expect(load).toHaveBeenCalledTimes(1);
  expect(wrapper.findAll('.finder-types button')).toHaveLength(6);
  await wrapper.find('.drawer-close').trigger('click'); expect(wrapper.emitted('close')).toHaveLength(1);
});
it('finds media beyond the initial page, then reuses the complete cache across type filters', async () => {
  const load = vi.fn().mockResolvedValueOnce(page(Array.from({ length: 25 }, (_, index) => row(200 - index)), '100', true))
    .mockResolvedValueOnce(page([row(90, [{ type: 'image', attachment_id: 'image' }]), row(89, [{ type: 'video', attachment_id: 'video' }]), row(88, [{ type: 'file', attachment_id: 'file' }]), row(87, [{ type: 'audio', attachment_id: 'audio' }])]));
  render(load); await flushPromises();
  await wrapper.findAll('.finder-types button')[2].trigger('click'); await waitFilter();
  expect(load).toHaveBeenCalledTimes(2); expect(load.mock.calls[1][0]).toBe('100');
  expect(wrapper.findAll('.finder-record')).toHaveLength(1); expect(wrapper.find('.private-part').text()).toBe('image');
  expect(wrapper.findComponent({ name: 'AttachmentPart' }).props('scopeKey')).toBe('account:conversation:history:90');
  for (const [index, type] of [[3, 'video'], [4, 'file'], [5, 'audio']]) {
    await wrapper.findAll('.finder-types button')[index].trigger('click'); await waitFilter();
    expect(wrapper.findAll('.finder-record')).toHaveLength(1); expect(wrapper.find('.private-part').text()).toBe(type);
  }
  expect(load).toHaveBeenCalledTimes(2);
});
it('combines Beijing dates with keywords and highlights literal text safely', async () => {
  const load = vi.fn().mockResolvedValue(page([
    row(3, [{ type: 'text', text: '<img> 报警' }], '2026-11-03T16:00:00Z'),
    row(2, [{ type: 'text', text: '<img> 报警' }], '2026-11-03T15:59:59Z'),
    row(1, [{ type: 'text', text: '正常' }], '2026-11-04 10:00:00'),
  ]));
  render(load); await flushPromises();
  await wrapper.find('input[type="date"]').setValue('2026-11-04'); await waitFilter();
  await wrapper.find('input[type="search"]').setValue('<img>'); await wrapper.find('form').trigger('submit'); await flushPromises();
  expect(wrapper.findAll('.finder-record')).toHaveLength(1); expect(wrapper.find('mark').text()).toBe('<img>'); expect(wrapper.find('img').exists()).toBe(false);
  await wrapper.find('.finder-record-main').trigger('click'); expect(wrapper.emitted('locate')[0][0].id).toBe('3');
});
it('aborts an old filter and ignores a late response even if the transport ignores cancellation', async () => {
  let finish;
  const load = vi.fn().mockImplementationOnce(() => new Promise(resolve => finish = resolve))
    .mockResolvedValueOnce(page([row(2, [{ type: 'image', attachment_id: 'image' }])]));
  render(load); await flushPromises(); const oldSignal = load.mock.calls[0][1];
  await wrapper.findAll('.finder-types button')[2].trigger('click'); await waitFilter(); expect(oldSignal.aborted).toBe(true);
  finish(page([row(1, [{ type: 'text', text: 'Private stale message' }])]));
  await flushPromises(); expect(wrapper.text()).not.toContain('Private stale message'); expect(wrapper.findAll('.finder-record')).toHaveLength(1);
});
it('clears cached messages and filters when the identity or conversation scope changes', async () => {
  const load = vi.fn().mockResolvedValueOnce(page([row(1, [{ type: 'text', text: 'Old account private message' }])]))
    .mockResolvedValue(page([row(2, [{ type: 'text', text: 'New account message' }])]));
  render(load); await flushPromises();
  await wrapper.find('input[type="search"]').setValue('private'); await wrapper.find('form').trigger('submit'); await flushPromises();
  await wrapper.setProps({ scopeKey: 'new-account:conversation' }); await flushPromises();
  expect(wrapper.find('input[type="search"]').element.value).toBe(''); expect(wrapper.text()).not.toContain('Old account private message'); expect(wrapper.text()).toContain('New account message');
});
it('keeps existing results on failure and retries the same earlier page', async () => {
  const load = vi.fn().mockResolvedValueOnce(page(Array.from({ length: 20 }, (_, i) => row(200 - i)), '100', true))
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce(page(Array.from({ length: 20 }, (_, i) => row(100 - i))));
  render(load); await flushPromises();
  await wrapper.find('.finder-more').trigger('click'); await flushPromises();
  expect(wrapper.find('[role="alert"]').exists()).toBe(true); expect(wrapper.findAll('.finder-record')).toHaveLength(20);
  await wrapper.find('[role="alert"] button').trigger('click'); await flushPromises();
  expect(wrapper.find('[role="alert"]').exists()).toBe(false); expect(wrapper.findAll('.finder-record')).toHaveLength(40);
  expect(load.mock.calls.slice(1).map(call => call[0])).toEqual(['100', '100']);
});
it('stops a non-advancing cursor rather than repeatedly requesting the same history', async () => {
  const load = vi.fn().mockResolvedValueOnce(page(Array.from({ length: 20 }, (_, i) => row(200 - i)), '100', true))
    .mockResolvedValue(page([], '100', true));
  render(load); await flushPromises(); await wrapper.find('.finder-more').trigger('click'); await flushPromises();
  expect(wrapper.find('[role="alert"]').exists()).toBe(true); expect(load).toHaveBeenCalledTimes(2);
});
it('aborts a pending read on close and cannot emit a late result after unmount', async () => {
  let finish;
  const load = vi.fn(() => new Promise(resolve => finish = resolve));
  render(load); await flushPromises(); const signal = load.mock.calls[0][1];
  wrapper.unmount(); expect(signal.aborted).toBe(true);
  finish(page([row(1)])); await flushPromises(); expect(wrapper.emitted('locate')).toBeUndefined();
});

it('shows searching during the keyword debounce instead of briefly claiming there are no matching messages', async () => {
  const load = vi.fn().mockResolvedValueOnce(page(Array.from({length: 20}, (_, index) => row(100 - index)), '50', true))
    .mockResolvedValueOnce(page([row(1, [{ type: 'text', text: 'older match' }])]));
  render(load); await flushPromises();
  await wrapper.find('input[type="search"]').setValue('older');
  expect(wrapper.find('.finder-results').attributes('aria-busy')).toBe('true');
  expect(wrapper.find('.finder-empty').text()).toBe('historyFinder.searching');
  await new Promise(resolve => setTimeout(resolve, 270)); await flushPromises();
  expect(wrapper.find('.finder-results').attributes('aria-busy')).toBe('false');
  expect(wrapper.findAll('.finder-record')).toHaveLength(1); expect(wrapper.find('.finder-record').text()).toContain('older match');
});

it('labels support, expert and system messages by their actual sender roles', async () => {
  render(vi.fn().mockResolvedValue(page([
    {...row(3),sender_type:'support_agent'}, {...row(2),sender_type:'expert'}, {...row(1),sender_type:'system'},
  ])));
  await flushPromises();
  expect(wrapper.findAll('.finder-record-meta strong').map(node=>node.text())).toEqual(['support.agent','support.expert','support.system']);
});
