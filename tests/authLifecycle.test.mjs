import { createRouter, createMemoryHistory } from 'vue-router';
import { flushPromises } from '@vue/test-utils';
import { beforeEach, afterEach, describe, expect, it } from 'vitest';
import { pinia, useAuthStore } from '../src/stores/auth';
import { watchAuthRoutes } from '../src/router/authLifecycle';

let router, auth, stop;
beforeEach(async () => {
  localStorage.clear();
  auth = useAuthStore(pinia); auth.clearAuth();
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/login', name: 'login', component: {} },
    { path: '/register', name: 'Register', component: {} },
    { path: '/home', name: 'home', component: {} },
    { path: '/conversations', name: 'ConversationWorkspace', component: {} },
  ] });
  await router.push('/login');
  stop = watchAuthRoutes(auth, router);
});
afterEach(() => stop());
const login = () => auth.setToken({ token: 'valid', expire: '7200', clientHash: 'hash' });
describe('active protected route authentication lifecycle', () => {
  it.each(['/home', '/conversations'])('immediately leaves %s when authentication is cleared', async path => {
    login(); auth.setUserInfo({ username: 'user', superAdmin: true });
    await router.push(path);
    auth.clearAuth(); await flushPromises();
    expect(router.currentRoute.value.name).toBe('login');
    expect(auth.userInfo).toEqual({});
    expect(localStorage.getItem('token')).toBeNull();
  });
  it('redirects when an invalid replacement DTO clears authentication', async () => {
    login(); await router.push('/home');
    auth.setToken({ token: 'invalid', expire: '-1' }); await flushPromises();
    expect(router.currentRoute.value.name).toBe('login');
  });
  it('retains valid protected navigation during token refresh and shares one state source', async () => {
    login(); auth.setUserInfo({ username: 'current' }); await router.push('/home');
    const sameStore = useAuthStore(pinia);
    sameStore.setToken({ token: 'refreshed', expire: '7200' }); await flushPromises();
    expect(sameStore).toBe(auth);
    expect(router.currentRoute.value.name).toBe('home');
    expect(auth.userInfo.username).toBe('current');
  });
  it('keeps anonymous registration accessible without authentication', async () => {
    await router.push('/register'); await flushPromises();
    expect(router.currentRoute.value.name).toBe('Register');
  });
});
