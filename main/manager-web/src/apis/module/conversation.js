import store from '../../store';
import { getServiceUrl } from '../api';
import { createConversationClient } from './conversationClient.mjs';

export default createConversationClient({
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
