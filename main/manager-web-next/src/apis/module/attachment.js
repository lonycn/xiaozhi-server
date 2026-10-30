import { authenticatedFetch, triggerSilentRefresh } from '../httpRequest';
import store from '../../store';
import { getServiceUrl } from '../serviceUrl';
import { createAttachmentClient } from './attachmentClient.mjs';

function token() { try { return JSON.parse(store.getters.getToken || '{}'); } catch { return {}; } }
export default createAttachmentClient({
  baseUrl: getServiceUrl(), fetchImpl: authenticatedFetch, getToken: () => token().token,
  prepareUpload: async () => {
    const dto = token();
    if (dto.expire && (dto._localStoredAt || 0) + dto.expire * 1000 - Date.now() < 3600000) await triggerSilentRefresh();
  },
  onUnauthorized: () => { store.commit('clearAuth'); window.location.hash = '/login'; },
});
