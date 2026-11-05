import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, afterEach, it, expect, vi } from 'vitest';
import Page from '../src/views/SupportSettings.vue';
import { useAuthStore } from '../src/stores/auth';
import { createSupportClient } from '../src/apis/module/supportClient.mjs';
const api = vi.hoisted(() => ({ agents: vi.fn(), profile: vi.fn(), me: vi.fn(), presence: vi.fn(), queue: vi.fn() }));
vi.mock('../src/apis/module/support', () => ({ default: api }));
let wrapper, auth;
const account = { user_id: '9223372036854775806', username: 'Expert', role: 'expert', enabled: true, capacity_text: '3' };
const page = (items = [account]) => ({ items, next_cursor: '0', has_more: false });
const render = () => wrapper = mount(Page, { global: { mocks: { $t: key => key }, stubs: { ElButton: { props: ['disabled', 'loading', 'nativeType'], template: '<button :type="nativeType" :disabled="disabled"><slot /></button>' }, ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' }, ElInput: true, ElSelect: true, ElOption: true, ElSwitch: true, ElInputNumber: true, ElTable: true, ElTableColumn: true } } });
beforeEach(() => { setActivePinia(createPinia()); auth = useAuthStore(); auth.token = JSON.stringify({ token: 'admin-token' }); auth.userInfo = { id: '1', superAdmin: '1' }; Object.values(api).forEach(fn => fn.mockReset()); api.agents.mockResolvedValue(page()); api.profile.mockResolvedValue(account); });
afterEach(() => { wrapper?.unmount(); wrapper = null; vi.restoreAllMocks(); });
it('loads profiles independently of expert duty and never changes presence or fetches customer queues', async () => {
  render(); await flushPromises(); expect(wrapper.vm.agents).toEqual([account]);
  expect(api.me).not.toHaveBeenCalled(); expect(api.presence).not.toHaveBeenCalled(); expect(api.queue).not.toHaveBeenCalled();
});
it('denies ordinary and external accounts without reading privileged profiles', async () => {
  auth.userInfo = { superAdmin: 0 }; render(); await flushPromises(); expect(api.agents).not.toHaveBeenCalled(); expect(wrapper.find('form').exists()).toBe(false);
  wrapper.unmount(); auth.userInfo = { superAdmin: 1 }; auth.token = JSON.stringify({ token: 'xext_external' }); render(); await flushPromises(); expect(api.agents).not.toHaveBeenCalled(); expect(wrapper.find('form').exists()).toBe(false);
});
it('edits a precise 64-bit account ID and saves the existing profile contract only', async () => {
  render(); await flushPromises(); wrapper.vm.editProfile(account); expect(wrapper.vm.edit.capacity_text).toBe(3);
  await wrapper.vm.saveProfile();
  expect(api.profile).toHaveBeenCalledWith('9223372036854775806', { role: 'expert', roles: ['expert'], enabled: true, capacity_text: 3, profile: { display_name: '', avatar_url: '', title: '', bio: '', specialties: [], listed: true } }, expect.any(AbortSignal));
  expect(wrapper.vm.saved).toBe(true); expect(wrapper.vm.editingAccount).toBe(null); expect(wrapper.find('form').exists()).toBe(false); expect(api.agents).toHaveBeenCalledTimes(2); expect(api.presence).not.toHaveBeenCalled();
});
it('loads every profile page using precise decreasing cursors', async () => {
  api.agents.mockResolvedValueOnce({ items: [account], next_cursor: '9223372036854775806', has_more: true }).mockResolvedValueOnce(page([{ ...account, user_id: '2' }]));
  render(); await flushPromises(); expect(api.agents.mock.calls.map(call => call[0])).toEqual(['0', '9223372036854775806']); expect(wrapper.vm.agents).toHaveLength(2);
});
it('clears records and ignores late data when administrator permission changes', async () => {
  let finish; api.agents.mockImplementationOnce(() => new Promise(resolve => finish = resolve)); render(); const signal = api.agents.mock.calls[0][1];
  auth.userInfo = { id: '1', superAdmin: 0 }; expect(signal.aborted).toBe(true); finish(page()); await flushPromises();
  expect(wrapper.vm.agents).toEqual([]); expect(wrapper.find('form').exists()).toBe(false);
});
it('retains failed edits and blocks an invalid capacity or user ID', async () => {
  render(); await flushPromises(); wrapper.vm.editProfile(account); wrapper.vm.edit.capacity_text = 0; await wrapper.vm.saveProfile(); expect(api.profile).not.toHaveBeenCalled();
  wrapper.vm.edit.capacity_text = 3; api.profile.mockRejectedValueOnce(new Error('Save failed')); await wrapper.vm.saveProfile(); expect(wrapper.vm.editingAccount.id).toBe(account.user_id); expect(wrapper.vm.saved).toBe(false); expect(wrapper.text()).toContain('Save failed');
});
it('server-side denial clears the configuration instead of retaining stale controls', async () => {
  api.agents.mockRejectedValueOnce(Object.assign(new Error('Forbidden'), { status: 403 })); render(); await flushPromises(); expect(wrapper.vm.agents).toEqual([]); expect(wrapper.find('form').exists()).toBe(false);
});
it('unmount aborts an in-flight save', async () => {
  let finish; api.profile.mockImplementationOnce(() => new Promise(resolve => finish = resolve)); render(); await flushPromises(); wrapper.vm.editProfile(account); const pending = wrapper.vm.saveProfile();
  const signal = api.profile.mock.calls[0][2]; wrapper.unmount(); wrapper = null; finish(account); await pending; expect(signal.aborted).toBe(true); expect(api.agents).toHaveBeenCalledTimes(1);
});
it('a late unauthorized profile reply cannot log out a new identity', async () => {
  let token = 'old', finish; const unauthorized = vi.fn(); const client = createSupportClient({ baseUrl: '/xiaozhi', getToken: () => token, onUnauthorized: unauthorized, fetchImpl: () => new Promise(resolve => finish = resolve) });
  const pending = client.agents(); token = 'new'; finish({ status: 401, ok: false, json: async () => ({ code: 401 }) });
  await expect(pending).rejects.toMatchObject({ name: 'AbortError' }); expect(unauthorized).not.toHaveBeenCalled();
});

it('saves independent staff roles without changing a retained legacy primary role', async () => {
  render(); await flushPromises(); wrapper.vm.editProfile({...account,roles:['support_agent','expert']});
  await wrapper.vm.saveProfile(); expect(api.profile.mock.calls[0][1]).toEqual({role:'expert',roles:['support_agent','expert'],enabled:true,capacity_text:3,profile:{display_name:'',avatar_url:'',title:'',bio:'',specialties:[],listed:true}});
  wrapper.vm.editProfile(account); wrapper.vm.edit.roles=[]; await wrapper.vm.saveProfile(); expect(api.profile).toHaveBeenCalledTimes(1);
  wrapper.vm.edit.roles=['support_agent']; await wrapper.vm.saveProfile(); expect(api.profile.mock.calls[1][1].role).toBe('support_agent');
});

it('saves display details without mutating the listed profile before a successful save', async () => {
  const profile = { display_name: '王工程师', avatar_url: 'https://example.com/avatar.png', title: '工程师', bio: '制冷调试', specialties: ['冷库'], listed: true };
  api.agents.mockResolvedValue(page([{ ...account, profile }]));
  render(); await flushPromises(); wrapper.vm.editProfile(wrapper.vm.agents[0]);
  wrapper.vm.edit.profile.display_name = '王专家'; wrapper.vm.specialtiesText = '冷库、压缩机，冷库'; wrapper.vm.edit.profile.listed = false;
  expect(wrapper.vm.agents[0].profile).toEqual(profile);
  await wrapper.vm.saveProfile();
  expect(api.profile.mock.calls[0][1].profile).toMatchObject({ display_name: '王专家', specialties: ['冷库','压缩机'], listed: false });
});
it('rejects oversized specialty lists before saving', async () => {
  render(); await flushPromises(); wrapper.vm.editProfile(account);
  wrapper.vm.edit.profile.specialties = Array.from({length:13}, (_,i) => `area-${i}`);
  await wrapper.vm.saveProfile(); expect(api.profile).not.toHaveBeenCalled();
});

it('shows the editor only for a selected account and discards canceled changes', async () => {
  render(); await flushPromises(); expect(wrapper.find('form').exists()).toBe(false);
  wrapper.vm.editProfile(account); await flushPromises();
  expect(wrapper.find('form').exists()).toBe(true); expect(wrapper.text()).toContain('Expert');
  expect(wrapper.html()).not.toContain('support.userId');
  wrapper.vm.edit.profile.display_name = 'Unsubmitted name';
  wrapper.vm.closeEditor(); await flushPromises();
  expect(wrapper.find('form').exists()).toBe(false); expect(wrapper.vm.editingAccount).toBe(null);
  await wrapper.vm.saveProfile(); expect(api.profile).not.toHaveBeenCalled();
  wrapper.vm.editProfile(account); expect(wrapper.vm.edit.profile.display_name).toBe('');
});
it('locks saves to the selected account and excludes any injected account ID from the profile body', async () => {
  render(); await flushPromises(); wrapper.vm.editProfile(account);
  wrapper.vm.edit.user_id = '2'; await wrapper.vm.saveProfile();
  expect(api.profile.mock.calls[0][0]).toBe(account.user_id);
  expect(api.profile.mock.calls[0][1]).not.toHaveProperty('user_id');
});
