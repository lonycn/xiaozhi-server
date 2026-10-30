import store from '../store';
import { validToken } from '../stores/auth';
import i18n from '../i18n';
import { showDanger, showWarning } from '../utils';

const baseUrl = () => import.meta.env.VITE_API_BASE_URL || '/xiaozhi';
let refreshPromise;
function tokenDTO() { try { return JSON.parse(store.getters.getToken || 'null'); } catch { return null; } }
function unauthorized() { store.commit('clearAuth'); window.location.hash = '/login'; }
export function triggerSilentRefresh() {
  if (!refreshPromise) {
    const original = store.getters.getToken;
    const dto = tokenDTO();
    refreshPromise = fetch(`${baseUrl()}/user/refresh`, { method: 'POST', headers: { Authorization: `Bearer ${dto?.token || ''}`, 'Content-Type': 'application/json' }, body: '{}', signal: AbortSignal.timeout(30000) })
      .then(async response => {
        const body = await response.json();
        if (!response.ok || ![0, 'success'].includes(body?.code) || !validToken(body.data)) throw new Error(body?.msg || '登录已过期，请重新登录');
        // An in-flight refresh must never restore an account after logout/account change.
        if (store.getters.getToken !== original) throw new Error('身份已变更，请重试');
        store.commit('setToken', { ...body.data, _localStoredAt: Date.now() });
        return body.data;
      }).catch(error => { if (store.getters.getToken === original) unauthorized(); throw error; })
      .finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}
export async function authenticatedFetch(url, options = {}) {
  const isPublic = /\/user\/(?:login|captcha|pub-config|register|smsVerification|retrieve-password)(?:\?|$)/.test(url);
  const dto = tokenDTO();
  if (!isPublic && dto?.expire && (dto._localStoredAt || 0) + dto.expire * 1000 - Date.now() < 3600000) await triggerSilentRefresh();
  const headers = new Headers(options.headers);
  const bearer = tokenDTO()?.token;
  if (bearer && !isPublic) headers.set('Authorization', `Bearer ${bearer}`);
  else headers.delete('Authorization');
  const lang = i18n.locale.replace('_', '-');
  headers.set('Accept-Language', lang === 'en' ? 'en-US' : lang);
  const response = await fetch(url, { ...options, headers, signal: options.signal || AbortSignal.timeout(30000) });
  if (response.status === 401 && !isPublic) unauthorized();
  return response;
}
function sendRequest() {
  return {
    _method: 'GET', _data: {}, _header: { 'Content-Type': 'application/json; charset=utf-8' },
    url(value) { this._url = value?.replaceAll('$', '/'); return this; },
    method(value) { this._method = value.toUpperCase(); return this; },
    data(value) { this._data = value; return this; },
    header(value) { const headers = new Headers(this._header); new Headers(value).forEach((entry, key) => headers.set(key, entry)); this._header = headers; return this; },
    type(value) { this._type = value; return this; },
    success(fn) { this._success = fn; return this; },
    fail(fn) { this._fail = fn; return this; },
    networkFail(fn) { this._networkFail = fn; return this; },
    showLoading() { return this; }, async() { return this; },
    send() {
      this.promise = Promise.resolve().then(async () => {
        let url = this._url;
        const options = { method: this._method, headers: new Headers(this._header) };
        if (['GET', 'HEAD'].includes(this._method)) {
          const query = new URLSearchParams();
          Object.entries(this._data || {}).forEach(([key, value]) => { if (value !== undefined && value !== null) query.append(key, value); });
          if (query.size) url += (url.includes('?') ? '&' : '?') + query;
        } else if (this._data instanceof FormData || this._data instanceof Blob) {
          options.body = this._data;
          if (this._data instanceof FormData) options.headers.delete('Content-Type');
          else if (options.headers.get('Content-Type')?.startsWith('application/json')) options.headers.set('Content-Type', this._data.type || 'application/octet-stream');
        } else if (this._data instanceof ArrayBuffer || ArrayBuffer.isView(this._data)) {
          options.body = this._data;
          if (options.headers.get('Content-Type')?.startsWith('application/json')) options.headers.set('Content-Type', 'application/octet-stream');
        } else options.body = JSON.stringify(this._data);
        const response = await authenticatedFetch(url, options);
        let data;
        if (response.ok && this._type === 'blob') data = await response.blob();
        else if (response.ok && this._type === 'arraybuffer') data = await response.arrayBuffer();
        else if (response.ok && this._type === 'text') data = await response.text();
        else data = await response.json().catch(() => null);
        return { status: response.status, data, headers: response.headers, ok: response.ok };
      }).catch(error => ({ status: 0, data: { msg: error.message }, error, networkError: true }))
        .then(result => {
          // Callbacks run outside the transport catch: an application callback
          // exception must never invoke a second callback or replay a mutation.
          if (result.networkError) {
            if (this._networkFail) this._networkFail(result);
            else if (this._fail) this._fail(result);
            else showDanger(result.data.msg || '网络连接失败');
          } else if (!result.ok || (result.data?.code !== undefined && ![0, 'success'].includes(result.data.code))) {
            if (result.data?.code === 401) unauthorized();
            if (this._fail) this._fail(result);
            else showDanger(result.data?.msg || `请求失败 (${result.status})`);
          } else this._success?.(result);
          return result;
        });
      return this;
    },
  };
}
let requestTime = 0;
function reAjaxFun(fn) { const elapsed = Date.now() - (requestTime ||= Date.now()); if (elapsed < 20000 && fn) { showWarning('正在连接服务器'); setTimeout(fn, 2000); } else showWarning('似乎无法连接服务器'); }
function clearRequestTime() { requestTime = 0; }
export default { sendRequest, reAjaxFun, clearRequestTime, triggerSilentRefresh };
