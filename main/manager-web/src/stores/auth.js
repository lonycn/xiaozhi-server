import { acceptHMRUpdate, createPinia, defineStore } from 'pinia';

// Keep one Pinia instance when Vite replaces the store module.
export const pinia = import.meta.hot?.data.pinia || createPinia();
if (import.meta.hot) import.meta.hot.data.pinia = pinia;
const defaults = () => ({ version: '', beianIcpNum: 'null', beianGaNum: 'null', allowUserRegister: false, sm2PublicKey: '' });
function read(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) || fallback; } catch { localStorage.removeItem(key); return fallback; }
}
// The manager API serializes Java long values as decimal strings. Normalize
// bounded expiry seconds only; IDs and cursors retain their original precision.
export function normalizeTokenDTO(dto) {
  if (!dto || typeof dto !== 'object' || Array.isArray(dto)) return null;
  let expire = dto.expire;
  if (typeof expire === 'string') {
    if (!/^[0-9]+$/.test(expire)) return null;
    expire = Number(expire);
  }
  if (typeof dto.token !== 'string' || !dto.token.trim()
    || !Number.isSafeInteger(expire) || expire <= 0 || !Number.isSafeInteger(expire * 1000)
    || !Number.isSafeInteger((dto._localStoredAt ?? Date.now()) + expire * 1000)
    || (dto.clientHash != null && typeof dto.clientHash !== 'string')
    || (dto._localStoredAt != null && (!Number.isSafeInteger(dto._localStoredAt) || dto._localStoredAt <= 0 || !Number.isSafeInteger(dto._localStoredAt + expire * 1000)))) return null;
  return { ...dto, expire };
}
export function validToken(dto) { return normalizeTokenDTO(dto) !== null; }
export const useAuthStore = defineStore('auth', {
  state: () => ({ token: '', userInfo: {}, pubConfig: defaults() }),
  getters: { bearer: state => { try { return JSON.parse(state.token).token || ''; } catch { return ''; } } },
  actions: {
    restore() {
      const dto = read('token', null);
      if (validToken(dto)) this.setToken(dto);
      else this.clearAuth();
      this.userInfo = this.token ? read('userInfo', {}) : {};
      this.pubConfig = { ...defaults(), ...read('pubConfig', {}) };
    },
    setToken(raw) {
      let dto;
      try { dto = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch { this.clearAuth(); return; }
      dto = normalizeTokenDTO(dto);
      if (!dto) { this.clearAuth(); return; }
      dto = { ...dto, _localStoredAt: dto._localStoredAt || Date.now() };
      this.token = JSON.stringify(dto);
      localStorage.setItem('token', this.token);
    },
    setUserInfo(value) { this.userInfo = value || {}; localStorage.setItem('userInfo', JSON.stringify(this.userInfo)); },
    setPubConfig(value) { this.pubConfig = { ...defaults(), ...value }; localStorage.setItem('pubConfig', JSON.stringify(this.pubConfig)); },
    clearAuth() { this.token = ''; this.userInfo = {}; localStorage.removeItem('token'); localStorage.removeItem('userInfo'); },
    async logout() { this.clearAuth(); const { default: router } = await import('../router'); await router.replace('/login'); },
    async fetchPubConfig() {
      const { default: Api } = await import('../apis/api');
      return new Promise(resolve => Api.user.getPubConfig(({ data }) => { this.setPubConfig(data.data); resolve(); }, () => resolve()));
    },
  },
});

if (import.meta.hot) import.meta.hot.accept(acceptHMRUpdate(useAuthStore, import.meta.hot));
