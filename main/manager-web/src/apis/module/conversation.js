import { authenticatedFetch } from '../httpRequest';
import store from '../../store';
import { getServiceUrl } from '../serviceUrl';
import { createConversationClient } from './conversationClient.mjs';

import i18n from '../../i18n';
import { supportErrorKey } from '../../utils/supportErrors.mjs';

export default createConversationClient({
  errorMessage: (code, status) => i18n.t(supportErrorKey(code, status)),
  fetchImpl: authenticatedFetch,
  baseUrl: getServiceUrl() || '/xiaozhi',
  getToken: () => {
    try { return JSON.parse(store.getters.getToken || '{}').token; }
    catch { return null; }
  },
  onUnauthorized: () => {
    store.commit('clearAuth');
    window.location.hash = '/login';
  },
});
