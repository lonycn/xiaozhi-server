import { triggerSilentRefresh } from '../httpRequest';
import store from '../../store';
import i18n from '../../i18n';
import { getServiceUrl } from '../serviceUrl';
import { createToolStatusClient } from './toolStatusClient.mjs';

// Unlike the shared fetch wrapper, a stale 401 must not clear a newer identity.
// The client checks the captured token before applying its unauthorized callback.
async function prepare() {
  let dto;
  try { dto = JSON.parse(store.getters.getToken || 'null'); } catch { dto = null; }
  if (dto?.expire && (dto._localStoredAt || 0) + dto.expire * 1000 - Date.now() < 3600000) await triggerSilentRefresh();
}
async function scopedFetch(url, options) {
  const headers = new Headers(options.headers);
  const lang = i18n.locale.replace('_', '-');
  headers.set('Accept-Language', lang === 'en' ? 'en-US' : lang);
  return fetch(url, { ...options, headers });
}
export default createToolStatusClient({
  fetchImpl: scopedFetch,
  prepare,
  baseUrl: getServiceUrl() || '/xiaozhi',
  getToken: () => { try { return JSON.parse(store.getters.getToken || '{}').token; } catch { return null; } },
  onUnauthorized: () => { store.commit('clearAuth'); window.location.hash = '/login'; },
});
