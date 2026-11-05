import { afterAll, afterEach, beforeEach, expect, it, vi } from 'vitest';
import { ElMessage } from 'element-plus';
import { pinia, useAuthStore } from '../src/stores/auth';
const api=vi.hoisted(()=>({me:vi.fn(),get:vi.fn()}));
vi.mock('../src/apis/module/support',()=>({default:api}));
// Retired split-console settings must not redirect internal navigation.
vi.stubEnv('VITE_DEVICE_HISTORY_URL', 'https://retired-device.invalid/');
vi.stubEnv('VITE_DEVICE_HISTORY_CONSOLE_URL', 'https://retired-console.invalid/');
const { default: router } = await import('../src/router');
afterAll(() => vi.unstubAllEnvs());
let auth;
beforeEach(async()=>{
  auth=useAuthStore(pinia);auth.token=JSON.stringify({token:'staff'});auth.userInfo={id:'1'};api.me.mockReset();api.get.mockReset();
  vi.spyOn(ElMessage,'warning').mockImplementation(()=>{});await router.push('/home');
});
afterEach(()=>vi.restoreAllMocks());
it('redirects hardware compatibility links to the read-only device desk with filters intact',async()=>{
  await router.push('/conversations?source=hardware&agent_id=9223372036854775806&mac_address=mac');
  expect(router.currentRoute.value.path).toBe('/device-conversations');expect(router.currentRoute.value.query.agent_id).toBe('9223372036854775806');
});
it('keeps device menu navigation on the current site and preserves the deployment prefix', async () => {
  const origin = window.location.origin;
  await router.push({ name: 'DeviceConversations', query: { agent_id: 'agent-1' } });
  expect(router.currentRoute.value.name).toBe('DeviceConversations');
  expect(router.currentRoute.value.query.agent_id).toBe('agent-1');
  expect(window.location.origin).toBe(origin);
  const href = router.resolve({ name: 'DeviceConversations', query: { agent_id: 'agent-1' } }).href;
  expect(href).toBe('#/device-conversations?agent_id=agent-1');
  for (const base of ['https://console.example.test/', 'https://console.example.test/console/']) {
    const target = new URL(href, base);
    expect(target.origin).toBe(new URL(base).origin);
    expect(target.pathname).toBe(new URL(base).pathname);
    expect(target.hash).toBe('#/device-conversations?agent_id=agent-1');
  }
});
it('blocks direct staff URLs for external identities before reading internal staff profiles',async()=>{
  auth.token=JSON.stringify({token:'xext_external'});auth.userInfo={superAdmin:1};await router.push('/expert-conversations');
  expect(router.currentRoute.value.path).toBe('/home');expect(api.me).not.toHaveBeenCalled();
});
it('checks the current role for direct URLs and routes legacy expert request links using backend metadata',async()=>{
  api.me.mockResolvedValue({enabled:true,role:'expert',roles:['expert']});await router.push('/support');expect(router.currentRoute.value.path).toBe('/home');
  api.get.mockResolvedValue({request_id:'r',service_type:'expert'});await router.push('/support?request=r&tab=old');
  expect(router.currentRoute.value.path).toBe('/expert-conversations');expect(router.currentRoute.value.query).toEqual({request:'r',tab:'old'});
});
it('ignores a late role discovery reply belonging to a previous token',async()=>{
  let finish;api.me.mockReturnValue(new Promise(resolve=>finish=resolve));const pending=router.push('/support');
  await new Promise(resolve=>setTimeout(resolve,0));auth.token=JSON.stringify({token:'new'});finish({enabled:true,role:'support_agent'});await pending;
  expect(router.currentRoute.value.path).toBe('/home');
});
