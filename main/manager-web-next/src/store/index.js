// Transitional API: every read and mutation delegates to the single Pinia store.
import { pinia, useAuthStore } from '../stores/auth';
const auth = () => useAuthStore(pinia);
const store = {
  get state() { return auth().$state; },
  getters: {
    get getToken() { return auth().token; },
    get getUserInfo() { return auth().userInfo; },
    get getPubConfig() { return auth().pubConfig; },
  },
  commit(name, value) { if (typeof auth()[name] !== 'function') throw new Error(`Unknown mutation ${name}`); return auth()[name](value); },
  dispatch(name, value) { return Promise.resolve(this.commit(name, value)); },
  install(app) { app.config.globalProperties.$store = store; app.provide('store', store); },
};
export default store;
