<template><el-config-provider :locale="elementLocale"><router-view /></el-config-provider></template>
<script>
import i18n, { elementLocales } from './i18n';
export default {
  name: 'App',
  computed: { elementLocale() { return elementLocales[i18n.locale] || elementLocales.zh_CN; } },
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
