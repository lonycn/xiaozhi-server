import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import store from '../src/store';
import { pinia, useAuthStore } from '../src/stores/auth';
import { authenticatedFetch } from '../src/apis/httpRequest';
import Request from '../src/apis/httpRequest';
import { generateSm2KeyPairHex, sm2Encrypt, sm2Decrypt } from '../src/utils';
import i18n, { changeLanguage, elementLocales } from '../src/i18n';

const dto = (token, expire = 7200) => ({ token, expire, clientHash: 'hash', _localStoredAt: Date.now() });
const response = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
beforeEach(() => { localStorage.clear(); store.commit('clearAuth'); });
afterEach(() => vi.unstubAllGlobals());

describe('Pinia authentication foundation', () => {
  it('restores TokenDTO and delegates every legacy mutation to the same source', () => {
    localStorage.setItem('token', JSON.stringify(dto('restored')));
    useAuthStore(pinia).restore();
    expect(useAuthStore(pinia).bearer).toBe('restored');
    store.commit('setToken', dto('next'));
    expect(JSON.parse(store.state.token)).toMatchObject({ token: 'next', clientHash: 'hash' });
    expect(store.state.token).toBe(localStorage.getItem('token'));
    store.commit('clearAuth');
    expect(useAuthStore(pinia).bearer).toBe('');
    expect(localStorage.getItem('token')).toBeNull();
  });
  it('recovers from malformed storage without accepting invalid authentication', () => {
    localStorage.setItem('token', '{bad'); localStorage.setItem('pubConfig', '{bad');
    expect(() => useAuthStore(pinia).restore()).not.toThrow();
    expect(useAuthStore(pinia).bearer).toBe('');
    expect(store.state.pubConfig.allowUserRegister).toBe(false);
  });
  it.each([
    { token: 'x', expire: -1 }, { token: 'x', expire: '7200garbage' }, { token: 'x', expire: '9007199254740992' }, { token: 'x', expire: '9007199254740' }, { token: 'x', expire: 'Infinity' }, { token: 'x', expire: '1.5' },
    { token: 'x', expire: null }, { token: ' ', expire: 7200 },
    { token: 'x', expire: 7200, _localStoredAt: -5 },
    { token: 'x', expire: 7200, clientHash: {} },
    ['x'], { token: 'x', expire: Number.MAX_SAFE_INTEGER },
  ])('rejects structurally invalid persisted TokenDTO: %j', invalid => {
    localStorage.setItem('token', JSON.stringify(invalid));
    localStorage.setItem('userInfo', JSON.stringify({ superAdmin: true }));
    useAuthStore(pinia).restore();
    expect(useAuthStore(pinia).bearer).toBe('');
    expect(localStorage.getItem('token')).toBeNull();
    expect(localStorage.getItem('userInfo')).toBeNull();
  });
  it('restores and normalizes backend decimal-string expiry seconds', async () => {
    localStorage.setItem('token', JSON.stringify({ ...dto('backend-token'), expire: '7200' }));
    useAuthStore(pinia).restore();
    expect(useAuthStore(pinia).bearer).toBe('backend-token');
    expect(JSON.parse(store.getters.getToken).expire).toBe(7200);
    store.commit('setToken', { ...dto('near-expiry'), expire: '30' });
    const transport = vi.fn(async url => url.endsWith('/refresh')
      ? response({ code: 0, data: { ...dto('refreshed-backend-token'), expire: '7200' } })
      : response({ code: 0, data: [] }));
    vi.stubGlobal('fetch', transport);
    await authenticatedFetch('/xiaozhi/a');
    expect(JSON.parse(store.getters.getToken).expire).toBe(7200);
    expect(useAuthStore(pinia).bearer).toBe('refreshed-backend-token');
    expect(transport.mock.calls[1][1].headers.get('Authorization')).toBe('Bearer refreshed-backend-token');
  });
  it('refreshes once for simultaneous requests and sends the refreshed bearer', async () => {
    store.commit('setToken', dto('old', 30));
    const transport = vi.fn(async url => url.endsWith('/refresh') ? response({ code: 0, data: dto('new') }) : response({ code: 0, data: [] }));
    vi.stubGlobal('fetch', transport);
    await Promise.all([authenticatedFetch('/xiaozhi/a'), authenticatedFetch('/xiaozhi/b')]);
    expect(transport.mock.calls.filter(([url]) => url.endsWith('/refresh'))).toHaveLength(1);
    for (const [, options] of transport.mock.calls.slice(1)) expect(options.headers.get('Authorization')).toBe('Bearer new');
  });
  it('clears identity on refresh failure and does not send pending business requests', async () => {
    store.commit('setToken', dto('old', 10));
    const transport = vi.fn(async () => response({ code: 401, msg: 'expired' }, 401));
    vi.stubGlobal('fetch', transport);
    await expect(authenticatedFetch('/xiaozhi/a')).rejects.toThrow('expired');
    expect(transport).toHaveBeenCalledTimes(1);
    expect(useAuthStore(pinia).bearer).toBe('');
  });
  it('forwards actual HTTP failures to the failure callback without empty success', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ code: 403, msg: 'denied' }, 403)));
    const success = vi.fn(), failure = vi.fn();
    const request = Request.sendRequest().url('/xiaozhi/forbidden').success(success).fail(failure).send();
    await request.promise;
    expect(success).not.toHaveBeenCalled();
    expect(failure).toHaveBeenCalledWith(expect.objectContaining({ status: 403, data: { code: 403, msg: 'denied' } }));
  });
  it('preserves real SM2 captcha-password composition and 04-prefixed C1C3C2', () => {
    const pair = generateSm2KeyPairHex();
    const encrypted = sm2Encrypt(pair.publicKey, 'Ab9secret');
    expect(encrypted.startsWith('04')).toBe(true);
    expect(sm2Decrypt(pair.privateKey, encrypted)).toBe('Ab9secret');
  });
  it('switches all six interface and component locales and persists selection', () => {
    for (const lang of ['zh_CN', 'zh_TW', 'en', 'de', 'vi', 'pt_BR']) {
      changeLanguage(lang);
      expect(i18n.locale).toBe(lang); expect(elementLocales[lang]).toBeTruthy();
      expect(localStorage.getItem('userLanguage')).toBe(lang);
      expect(i18n.global.te('login.login', lang)).toBe(true);
    }
  });
});

describe('legacy request transport compatibility', () => {
  it.each(['content-type', 'Content-Type', 'CONTENT-TYPE'])('removes multipart %s and lets fetch generate the boundary', async key => {
    const transport = vi.fn(async () => response({ code: 0, data: { uploaded: true } }));
    vi.stubGlobal('fetch', transport);
    const form = new FormData(); form.append('file', new Blob(['firmware']), 'test.bin');
    await Request.sendRequest().url('/xiaozhi/upload').method('POST').header({ [key]: 'multipart/form-data' }).data(form).send().promise;
    const options = transport.mock.calls[0][1];
    expect(options.body).toBe(form);
    expect(options.headers.has('content-type')).toBe(false);
  });
  it.each(['PUT', 'DELETE'])('sends %s JSON with its body and current authentication', async method => {
    store.commit('setToken', dto('current'));
    const transport = vi.fn(async () => response({ code: 0, data: null }));
    vi.stubGlobal('fetch', transport);
    await Request.sendRequest().url('/xiaozhi/items').method(method.toLowerCase()).data({ ids: [1, 2] }).send().promise;
    const options = transport.mock.calls[0][1];
    expect(options.method).toBe(method); expect(JSON.parse(options.body)).toEqual({ ids: [1, 2] });
    expect(options.headers.get('Content-Type')).toContain('application/json');
    expect(options.headers.get('Authorization')).toBe('Bearer current');
  });
  it.each(['blob', 'arraybuffer'])('returns intact binary %s response', async type => {
    const bytes = new Uint8Array([0, 1, 128, 255]);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(bytes, { headers: { 'Content-Type': 'application/octet-stream' } })));
    const success = vi.fn();
    await Request.sendRequest().url('/xiaozhi/download').type(type).success(success).send().promise;
    const value = success.mock.calls[0][0].data;
    const buffer = type === 'blob' ? await value.arrayBuffer() : value;
    expect(Array.from(new Uint8Array(buffer))).toEqual(Array.from(bytes));
  });
  it('does not call failure or network callbacks when a success callback itself throws', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ code: 0 })));
    const fail = vi.fn(), network = vi.fn();
    const request = Request.sendRequest().url('/xiaozhi/write').method('PUT')
      .success(() => { throw new Error('render error'); }).fail(fail).networkFail(network).send();
    await expect(request.promise).rejects.toThrow('render error');
    expect(fail).not.toHaveBeenCalled(); expect(network).not.toHaveBeenCalled();
  });
  it('does not call network callback when a business failure callback throws', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ code: 409, msg: 'conflict' }, 409)));
    const network = vi.fn();
    const request = Request.sendRequest().url('/xiaozhi/write').method('DELETE')
      .fail(() => { throw new Error('display error'); }).networkFail(network).send();
    await expect(request.promise).rejects.toThrow('display error');
    expect(network).not.toHaveBeenCalled();
  });
});
