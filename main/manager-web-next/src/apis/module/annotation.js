import { authenticatedFetch } from '../httpRequest';
import store from '../../store';
import { getServiceUrl } from '../serviceUrl';
import { createAnnotationClient } from './annotationClient.mjs';
export default createAnnotationClient({
  baseUrl: getServiceUrl(), fetchImpl: authenticatedFetch,
  getToken: () => { try { return JSON.parse(store.getters.getToken || '{}').token; } catch { return ''; } },
  onUnauthorized: () => { store.commit('clearAuth'); window.location.hash = '/login'; },
});
