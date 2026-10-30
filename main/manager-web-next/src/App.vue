<template><el-config-provider :locale="elementLocale"><router-view v-if="anonymous" /><AdminLayout v-else><router-view /></AdminLayout></el-config-provider></template>
<script>
import i18n, { elementLocales } from './i18n';
import AdminLayout from './layouts/AdminLayout.vue';
import { anonymousRouteNames } from './router/authLifecycle';
export default {
  name: 'App',
  components: { AdminLayout },
  computed: {
    anonymous() { return !this.$route.name || anonymousRouteNames.has(this.$route.name); },
    elementLocale() { return elementLocales[i18n.locale] || elementLocales.zh_CN; },
  },
  mounted() {
    if (/Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent) && import.meta.env.VITE_H5_URL) window.location.href = import.meta.env.VITE_H5_URL;
  },
};
</script>
<style lang="scss">
#app { font-family: Avenir, Helvetica, Arial, sans-serif; -webkit-font-smoothing: antialiased; -moz-osx-font-smoothing: grayscale; text-align: center; color: #2c3e50; }
.copyright { padding: 0 !important; color: #000; font-size: 12px; font-weight: 400; margin-top: auto; width: 100%; height: 100%; display: flex; justify-content: center; align-items: center; }
.el-message { top: 70px !important; }
</style>
