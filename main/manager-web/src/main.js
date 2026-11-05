import 'element-plus/dist/index.css';
import 'normalize.css/normalize.css';
import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import App from './App.vue';
import router from './router';
import { watchAuthRoutes } from './router/authLifecycle';
import store from './store';
import { pinia, useAuthStore } from './stores/auth';
import i18n from './i18n';
import { assetUrl } from './adapters/assets';
import LegacyIcon from './adapters/LegacyIcon.vue';
import { registerLegacyIcons } from './adapters/icons';
import './styles/global.scss';

useAuthStore(pinia).restore();
const app = createApp(App);
app.use(pinia).use(store).use(i18n).use(ElementPlus).use(router);
const stopAuthRouteWatcher = watchAuthRoutes(useAuthStore(pinia), router);
if (import.meta.hot) import.meta.hot.dispose(stopAuthRouteWatcher);
app.component('LegacyIcon', LegacyIcon);
registerLegacyIcons(app);
app.config.globalProperties.assetUrl = assetUrl;
// Retain the narrow legacy language notification API without a Vue instance bus.
const listeners = new Map();
app.config.globalProperties.$eventBus = {
  $on(name, fn) { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn); },
  $off(name, fn) { if (fn) listeners.get(name)?.delete(fn); else listeners.delete(name); },
  $emit(name, ...args) { listeners.get(name)?.forEach(fn => fn(...args)); },
};
app.mount('#app');
