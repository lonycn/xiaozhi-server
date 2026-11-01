import { mount, flushPromises } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import Panel from '../src/components/AgentToolStatus.vue';
import { createToolStatusClient } from '../src/apis/module/toolStatusClient.mjs';
const api = vi.hoisted(() => ({ list: vi.fn() }));
vi.mock('../src/apis/module/toolStatus', () => ({ default: api }));
const page = (items = [], next_cursor = '0', has_more = false) => ({ items, next_cursor, has_more });
const event = (sequence, call_id, status, extra = {}) => ({ sequence, call_id, name: 'device.read', status, attempt: 1, round: 1, created_at: '2026-11-01', ...extra });
let wrapper;
function panel() { wrapper = mount(Panel, { props: { conversationId: 'one', identity: 'user1' }, global: { mocks: { $t: key => key } } }); return wrapper; }
beforeEach(() => { vi.resetAllMocks(); api.list.mockResolvedValue(page()); });
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.useRealTimers(); });
describe('read-only tool status', () => {
  it('aggregates latest audit event per call and sends exact server cursor', async () => {
    api.list.mockResolvedValueOnce(page([event('9007199254740995','a','succeeded'),event('9','a','running')],'8',true))
      .mockResolvedValueOnce(page([event('7','b','failed', { error_code: 'UNAVAILABLE', arguments: 'secret', output: 'secret-output' })],'7',false));
    panel(); await flushPromises(); expect(wrapper.findAll('li')).toHaveLength(1); expect(wrapper.text()).toContain('toolStatus.succeeded');
    await wrapper.vm.loadMore(); expect(api.list).toHaveBeenLastCalledWith('one','8',expect.any(AbortSignal));
    expect(wrapper.findAll('li')).toHaveLength(2); expect(wrapper.text()).toContain('UNAVAILABLE'); expect(wrapper.text()).not.toContain('secret');
  });
  it('uses the new server cursor to fill a gap after a disjoint poll', async () => {
    api.list.mockResolvedValueOnce(page([event('2','old','running')],'2',false))
      .mockResolvedValueOnce(page([event('100','new','running')],'51',true))
      .mockResolvedValueOnce(page([event('50','middle','failed')],'2',false));
    panel(); await flushPromises(); await wrapper.vm.refresh(); await wrapper.vm.loadMore();
    expect(api.list).toHaveBeenLastCalledWith('one','51',expect.any(AbortSignal));
    expect(wrapper.findAll('li')).toHaveLength(3);
  });
  it('aborts and fences a late response across conversation and identity changes', async () => {
    let resolve; api.list.mockImplementationOnce(() => new Promise(r => { resolve = r; })).mockResolvedValue(page([event('2','new','running')]));
    panel(); const signal = api.list.mock.calls[0][2]; await wrapper.setProps({ conversationId: 'two', identity: 'user2' }); await flushPromises();
    expect(signal.aborted).toBe(true); resolve(page([event('10','old','failed', { name:'OLD_PRIVATE' })])); await flushPromises();
    expect(wrapper.text()).not.toContain('OLD_PRIVATE'); expect(wrapper.text()).toContain('device.read');
  });
  it('hides 404 and shows other errors, including after a previous success', async () => {
    api.list.mockRejectedValueOnce({ status:404 }); panel(); await flushPromises(); expect(wrapper.find('section').exists()).toBe(false);
    await wrapper.setProps({ conversationId:'two' }); api.list.mockRejectedValue({ status:500 }); await flushPromises(); await wrapper.vm.refresh(); expect(wrapper.text()).toContain('toolStatus.error');
  });
  it('polls only selected conversation and stops on unmount', async () => {
    vi.useFakeTimers(); panel(); await flushPromises(); expect(api.list).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(5000); expect(api.list).toHaveBeenCalledTimes(2);
    wrapper.unmount(); wrapper = null; await vi.advanceTimersByTimeAsync(10000); expect(api.list).toHaveBeenCalledTimes(2);
  });
});
describe('tool status client', () => {
  it('does not clear a newer identity on a stale unauthorized response', async () => {
    let token = 'old', resolve;
    const fetchImpl = vi.fn(() => new Promise(r => { resolve = r; }));
    const onUnauthorized = vi.fn();
    const client = createToolStatusClient({ baseUrl:'/root', getToken:() => token, fetchImpl, onUnauthorized });
    const pending = client.list('conv'); await Promise.resolve(); token = 'new';
    resolve({ ok:false, status:401, json:async()=>({code:401}) });
    await expect(pending).rejects.toMatchObject({status:401}); expect(onUnauthorized).not.toHaveBeenCalled();
  });
  it('preserves cursor and surfaces authentication and HTTP failure', async () => {
    const fetchImpl=vi.fn().mockResolvedValueOnce({ ok:true,status:200,json:async()=>({code:0,data:page()}) }).mockResolvedValueOnce({ ok:false,status:401,json:async()=>({code:401}) });
    const onUnauthorized=vi.fn(); const client=createToolStatusClient({baseUrl:'/root',getToken:()=> 'token',fetchImpl,onUnauthorized});
    await client.list('conv','9007199254740999'); expect(fetchImpl.mock.calls[0][0]).toContain('before=9007199254740999');
    await expect(client.list('conv')).rejects.toMatchObject({status:401}); expect(onUnauthorized).toHaveBeenCalledOnce();
  });
});
