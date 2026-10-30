import { afterEach, describe, expect, it, vi } from 'vitest';

afterEach(() => vi.resetModules());
describe('cold frontend module evaluation', () => {
  it.each([
    ['dictionary API first', () => import('../src/apis/module/dict')],
    ['user API first', () => import('../src/apis/module/user')],
    ['dictionary deep-link view first', () => import('../src/views/DictManagement.vue')],
    ['password dialog first', () => import('../src/components/ChangePasswordDialog.vue')],
  ])('loads %s without API aggregate initialization cycles', async (_, loadEntry) => {
    vi.resetModules();
    const entry = await loadEntry();
    expect(entry.default).toBeTruthy();
    const { default: api } = await import('../src/apis/api');
    expect(typeof api.dict.getDictTypeList).toBe('function');
    expect(typeof api.user.login).toBe('function');
    expect(api.getServiceUrl()).toBe('/xiaozhi');
  });
  it('resolves a fresh authenticated dictionary deep link through the actual router', async () => {
    vi.resetModules();
    const { pinia, useAuthStore } = await import('../src/stores/auth');
    const auth = useAuthStore(pinia);
    auth.setToken({ token: 'test-only-identity', expire: '7200' });
    const { default: router } = await import('../src/router');
    await router.push('/dict-management');
    expect(router.currentRoute.value.name).toBe('DictManagement');
    expect(router.currentRoute.value.matched[0].components.default).toBeTruthy();
    auth.clearAuth();
  });
});
