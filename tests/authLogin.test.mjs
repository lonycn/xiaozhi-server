import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import Login from '../src/views/login.vue';
import store from '../src/store';
import { pinia } from '../src/stores/auth';
import i18n from '../src/i18n';
import { generateSm2KeyPairHex, sm2Decrypt } from '../src/utils';
const user = vi.hoisted(() => ({ login: vi.fn(), getCaptcha: vi.fn(), getUserInfo: vi.fn(), getPubConfig: vi.fn() }));
vi.mock('../src/apis/api', () => ({ default: { user } }));
const pair = generateSm2KeyPairHex();
let wrapper;
beforeEach(async () => {
  vi.resetAllMocks(); localStorage.clear(); store.commit('clearAuth');
  user.getPubConfig.mockImplementation(callback => callback({ data: { code: 0, data: { sm2PublicKey: pair.publicKey, allowUserRegister: true, enableMobileRegister: false } } }));
  wrapper = mount(Login, { global: { plugins: [pinia, store, i18n, ElementPlus], mocks: { $route: { path: '/login' } }, stubs: { VersionFooter: true } } });
  await flushPromises();
});
afterEach(() => { wrapper.unmount(); });
describe('Vue 3 login form', () => {
  async function fill() {
    const inputs = wrapper.findAll('input');
    await inputs[0].setValue('normal-user'); await inputs[1].setValue('secret'); await inputs[2].setValue('AB9');
  }
  it('submits real SM2-encrypted captcha plus password from rendered inputs', async () => {
    await fill(); await wrapper.get('button.login-btn').trigger('click');
    expect(user.login).toHaveBeenCalledOnce();
    const [payload, success] = user.login.mock.calls[0];
    expect(payload.username).toBe('normal-user'); expect(payload.captchaId).toBe(wrapper.vm.captchaUuid);
    expect(payload.password.startsWith('04')).toBe(true);
    expect(sm2Decrypt(pair.privateKey, payload.password)).toBe('AB9secret');
    expect(wrapper.get('button.login-btn').attributes('disabled')).toBeDefined();
    success({ data: { code: 0, data: { token: 'normal-token', expire: 7200, clientHash: 'normal-hash' } } });
    expect(JSON.parse(store.getters.getToken)).toMatchObject({ token: 'normal-token', clientHash: 'normal-hash' });
    expect(user.getUserInfo).toHaveBeenCalledOnce();
  });
  it('keeps input on failure, refreshes captcha, and enables a new attempt', async () => {
    await fill(); await wrapper.get('button.login-btn').trigger('click');
    const fail = user.login.mock.calls[0][2];
    const oldId = wrapper.vm.captchaUuid;
    fail({ data: { msg: '验证码失效' } }); await flushPromises();
    expect(wrapper.vm.form.password).toBe('secret');
    expect(wrapper.vm.captchaUuid).not.toBe(oldId);
    expect(wrapper.get('button.login-btn').attributes('disabled')).toBeUndefined();
  });
});
