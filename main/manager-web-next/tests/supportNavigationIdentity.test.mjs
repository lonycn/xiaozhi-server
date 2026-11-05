import { shallowMount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { reactive } from 'vue';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import Layout from '../src/layouts/AdminLayout.vue';
import { useAuthStore } from '../src/stores/auth';
const api=vi.hoisted(()=>({me:vi.fn()}));
const routeState=vi.hoisted(()=>({route:null}));
vi.mock('../src/apis/module/support',()=>({default:api}));
vi.mock('../src/utils/featureManager',()=>({default:{waitForInitialization:vi.fn().mockResolvedValue()}}));
vi.mock('vue-router',async importOriginal=>({...await importOriginal(),useRoute:()=>routeState.route,useRouter:()=>({push:vi.fn().mockResolvedValue()})}));
let wrapper,auth;
const paths=()=>wrapper.vm.groups.flatMap(group=>group.items.map(item=>item.path));
beforeEach(()=>{routeState.route=reactive({path:'/home',fullPath:'/home'});setActivePinia(createPinia());auth=useAuthStore();auth.token=JSON.stringify({token:'first'});auth.userInfo={id:'1'};api.me.mockReset();});
afterEach(()=>{wrapper?.unmount();wrapper=null;vi.restoreAllMocks();localStorage.removeItem('adminSidebarCollapsed');});
it('clears old staff menus immediately and ignores the old profile after switching identities',async()=>{
  let finish;api.me.mockReturnValueOnce(new Promise(resolve=>finish=resolve)).mockResolvedValue({enabled:true,role:'expert',roles:['expert']});
  wrapper=shallowMount(Layout,{global:{stubs:Object.fromEntries(['ElMenu','ElMenuItem','ElMenuItemGroup','ElSubMenu','ElScrollbar','ElIcon','ElBreadcrumb','ElBreadcrumbItem','ElDropdown','ElDropdownItem','ElDropdownMenu','RouterLink'].map(name=>[name,true])),mocks:{$t:key=>key,$route:{path:'/home',fullPath:'/home'}}}});
  const signal=api.me.mock.calls[0][0];auth.token=JSON.stringify({token:'second'});auth.userInfo={id:'2'};
  expect(signal.aborted).toBe(true);expect(paths()).not.toContain('/support');
  await flushPromises();finish({enabled:true,role:'support_agent',roles:['support_agent']});await flushPromises();
  expect(paths()).toContain('/expert-conversations');expect(paths()).not.toContain('/support');
  auth.token=JSON.stringify({token:'xext_external'});expect(paths()).not.toContain('/expert-conversations');
  expect(paths()).not.toContain('/device-conversations');
});

function renderResponsive() {
  wrapper=shallowMount(Layout,{global:{stubs:Object.fromEntries(['ElMenu','ElMenuItem','ElMenuItemGroup','ElSubMenu','ElScrollbar','ElIcon','ElBreadcrumb','ElBreadcrumbItem','ElDropdown','ElDropdownItem','ElDropdownMenu','RouterLink'].map(name=>[name,true])),mocks:{$t:key=>key,$route:routeState.route}}});
  return wrapper;
}
function viewport(initialMobile) {
  let matches=initialMobile; const listeners=new Set();
  vi.spyOn(window,'matchMedia').mockImplementation(()=>({get matches(){return matches;},addEventListener:(type,fn)=>listeners.add(fn),removeEventListener:(type,fn)=>listeners.delete(fn)}));
  return async mobile=>{matches=mobile;for(const listener of listeners)listener({matches});window.dispatchEvent(new Event('resize'));await wrapper.vm.$nextTick();};
}
it('starts narrow navigation closed and exposes explicit open, close, backdrop and Escape controls',async()=>{
  viewport(true);renderResponsive();await wrapper.vm.$nextTick();
  const toggle=wrapper.find('button[aria-controls="admin-sidebar"]');
  expect(toggle.attributes('aria-expanded')).toBe('false');expect(toggle.attributes('aria-label')).toBe('打开导航菜单');
  expect(wrapper.find('#admin-sidebar').attributes('aria-hidden')).toBe('true');expect(wrapper.find('#admin-sidebar').attributes('inert')).toBeDefined();
  expect(wrapper.find('#admin-sidebar').element.style.display).toBe('none');expect(wrapper.find('.admin-menu-mask').exists()).toBe(false);
  await toggle.trigger('click');expect(toggle.attributes('aria-expanded')).toBe('true');expect(wrapper.find('#admin-sidebar').element.style.display).not.toBe('none');expect(wrapper.find('#admin-sidebar').attributes('aria-hidden')).toBe('false');
  expect(wrapper.find('.admin-menu-mask').exists()).toBe(true);expect(wrapper.find('.admin-mobile-menu-close').exists()).toBe(true);
  await wrapper.find('.admin-menu-mask').trigger('click');expect(toggle.attributes('aria-expanded')).toBe('false');
  await toggle.trigger('click');await wrapper.find('.admin-mobile-menu-close').trigger('click');expect(toggle.attributes('aria-expanded')).toBe('false');
  await toggle.trigger('click');await wrapper.trigger('keydown',{key:'Escape'});expect(toggle.attributes('aria-expanded')).toBe('false');
});
it('closes mobile navigation on route changes and breakpoint transitions without changing desktop collapse preference',async()=>{
  localStorage.setItem('adminSidebarCollapsed','true');const resize=viewport(false);renderResponsive();await wrapper.vm.$nextTick();
  expect(wrapper.classes()).toContain('is-collapsed');expect(wrapper.find('#admin-sidebar').element.style.display).not.toBe('none');
  await resize(true);const toggle=wrapper.find('button[aria-controls="admin-sidebar"]');expect(toggle.attributes('aria-expanded')).toBe('false');expect(wrapper.find('#admin-sidebar').element.style.display).toBe('none');
  await toggle.trigger('click');routeState.route.path='/support';routeState.route.fullPath='/support?request=r';await wrapper.vm.$nextTick();
  expect(toggle.attributes('aria-expanded')).toBe('false');expect(wrapper.find('.admin-menu-mask').exists()).toBe(false);
  await toggle.trigger('click');await resize(false);expect(wrapper.find('#admin-sidebar').element.style.display).not.toBe('none');expect(wrapper.classes()).toContain('is-collapsed');
  await resize(true);expect(toggle.attributes('aria-expanded')).toBe('false');expect(localStorage.getItem('adminSidebarCollapsed')).toBe('true');
  await resize(false);await toggle.trigger('click');expect(localStorage.getItem('adminSidebarCollapsed')).toBe('false');
});
