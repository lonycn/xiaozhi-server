import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import { nextTick } from 'vue'
import AdminLayout from '../src/layouts/AdminLayout.vue'
import { useAuthStore } from '../src/stores/auth' 

vi.mock('@/utils/featureManager', () => ({ default: { waitForInitialization: vi.fn() } }))
vi.mock('@/apis/module/support', () => ({ default: { me: vi.fn().mockResolvedValue({ enabled: false }) } }))

const stub = { template: '<div><slot/><slot name="title"/><slot name="dropdown"/></div>' }
describe('admin layout', () => {
  beforeEach(() => localStorage.removeItem('adminSidebarCollapsed'))
  it('tracks contextual tabs, preserves query and persists collapsed state', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: ['/home', '/role-config', '/device-management'].map(path => ({ path, component: { template: '<div />' } })) })
    await router.push('/role-config?id=1'); await router.isReady()
    const wrapper = mount(AdminLayout, { global: { plugins: [createPinia(), router], mocks: { $t: key => key }, stubs: { ElMenu: stub, ElMenuItem: stub, ElMenuItemGroup: stub, ElSubMenu: stub, ElScrollbar: stub, ElBreadcrumb: stub, ElBreadcrumbItem: stub, ElDropdown: stub, ElDropdownMenu: stub, ElDropdownItem: stub, ElIcon: stub, ChangePasswordDialog: true } } })
    expect(wrapper.find('[default-active]').attributes('default-active')).toBe('/home')
    await router.push('/device-management?id=2'); await nextTick()
    expect(wrapper.findAll('.admin-tab a').map(a => a.attributes('href'))).toContain('/role-config?id=1')
    await wrapper.findAll('.admin-tab-close').at(-1).trigger('click'); await flushPromises(); await nextTick()
    expect(router.currentRoute.value.fullPath).toBe('/role-config?id=1')
    await wrapper.find('.admin-icon-button').trigger('click')
    expect(localStorage.getItem('adminSidebarCollapsed')).toBe('true')
    wrapper.unmount()
  })
})

describe('mobile and account controls', () => {
  it('closes mobile overlay on Escape and navigation', async () => {
    const original = window.matchMedia
    const query = { matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }
    window.matchMedia = vi.fn(() => query)
    const router = createRouter({ history: createMemoryHistory(), routes: ['/home', '/role-config'].map(path => ({ path, component: { template: '<div />' } })) })
    await router.push('/home'); await router.isReady()
    const wrapper = mount(AdminLayout, { global: { plugins: [createPinia(), router], mocks: { $t: key => key }, stubs: { ElMenu: stub, ElMenuItem: stub, ElMenuItemGroup: stub, ElSubMenu: stub, ElScrollbar: stub, ElBreadcrumb: stub, ElBreadcrumbItem: stub, ElDropdown: stub, ElDropdownMenu: stub, ElDropdownItem: stub, ElIcon: stub, ChangePasswordDialog: true } } })
    try {
      await wrapper.find('.admin-icon-button').trigger('click')
      expect(wrapper.find('.admin-menu-mask').exists()).toBe(true)
      await wrapper.find('.admin-shell').trigger('keydown', { key: 'Escape' })
      expect(wrapper.find('.admin-menu-mask').exists()).toBe(false)
      await wrapper.find('.admin-icon-button').trigger('click')
      await router.push('/role-config'); await nextTick()
      expect(wrapper.find('.admin-menu-mask').exists()).toBe(false)
    } finally { wrapper.unmount(); window.matchMedia = original }
    expect(query.removeEventListener).toHaveBeenCalled()
  })
  it('opens password dialog and delegates logout', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/home', component: { template: '<div />' } }] })
    await router.push('/home'); await router.isReady()
    const pinia = createPinia()
    const auth = useAuthStore(pinia)
    const logout = vi.spyOn(auth, 'logout').mockResolvedValue()
    const dropdown = { name: 'ElDropdown', template: '<div><slot/><slot name="dropdown"/></div>' }
    const wrapper = mount(AdminLayout, { global: { plugins: [pinia, router], mocks: { $t: key => key }, stubs: { ElMenu: stub, ElMenuItem: stub, ElMenuItemGroup: stub, ElSubMenu: stub, ElScrollbar: stub, ElBreadcrumb: stub, ElBreadcrumbItem: stub, ElDropdown: dropdown, ElDropdownMenu: stub, ElDropdownItem: stub, ElIcon: stub, ChangePasswordDialog: { name: 'ChangePasswordDialog', props: ['modelValue'], template: '<div />' } } } })
    const account = wrapper.findAllComponents(dropdown)[1]
    account.vm.$emit('command', 'password'); await nextTick()
    expect(wrapper.findComponent({ name: 'ChangePasswordDialog' }).props('modelValue')).toBe(true)
    account.vm.$emit('command', 'logout'); await flushPromises()
    expect(logout).toHaveBeenCalledOnce()
    wrapper.unmount()
  })
})
