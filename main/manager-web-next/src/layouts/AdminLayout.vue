<template>
  <div class="admin-shell" :class="{ 'is-collapsed': collapsed, 'is-mobile': mobile, 'menu-open': mobileOpen }" @keydown.esc="closeMobileMenu">
    <button v-if="mobile && mobileOpen" class="admin-menu-mask" aria-label="关闭导航菜单" @click="closeMobileMenu" />
    <aside id="admin-sidebar" class="admin-sidebar" :inert="mobile && !mobileOpen" aria-label="主导航">
      <router-link to="/home" class="admin-brand" @click="closeMobileMenu">
        <img src="@/assets/xiaozhi-logo.png" alt="" />
        <strong v-if="!collapsed || mobile">智控台</strong>
      </router-link>
      <el-scrollbar class="admin-menu-scroll">
        <el-menu :default-active="pageInfo.menuPath" :collapse="collapsed && !mobile" :collapse-transition="false" background-color="transparent" text-color="#b9c5d6" active-text-color="#fff" @select="navigate">
          <template v-for="group in groups" :key="group.id">
            <el-sub-menu v-if="group.id === 'system'" :index="group.id">
              <template #title><el-icon><Setting /></el-icon><span>{{ $t(group.labelKey) }}</span></template>
              <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path"><el-icon><component :is="item.icon" /></el-icon><template #title>{{ $t(item.titleKey) }}</template></el-menu-item>
            </el-sub-menu>
            <el-menu-item-group v-else>
              <template #title><span v-if="!collapsed || mobile">{{ $t(group.labelKey) }}</span></template>
              <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path"><el-icon><component :is="item.icon" /></el-icon><template #title>{{ $t(item.titleKey) }}</template></el-menu-item>
            </el-menu-item-group>
          </template>
        </el-menu>
      </el-scrollbar>
      <div v-if="!collapsed || mobile" class="admin-sidebar-caption">xiaozhi-esp32-server</div>
    </aside>

    <div class="admin-frame">
      <header class="admin-topbar">
        <div class="admin-topbar-start">
          <button ref="menuToggle" class="admin-icon-button" :aria-label="mobile ? (mobileOpen ? '关闭导航菜单' : '打开导航菜单') : (collapsed ? '展开侧边栏' : '收起侧边栏')" aria-controls="admin-sidebar" :aria-expanded="mobile ? mobileOpen : !collapsed" @click="toggleMenu"><el-icon><Expand v-if="collapsed && !mobile" /><Fold v-else /></el-icon></button>
          <el-breadcrumb separator="/" class="admin-breadcrumb"><el-breadcrumb-item :to="{ path: pageInfo.menuPath || '/home' }">{{ $t(pageInfo.sectionKey || 'adminLayout.workspace') }}</el-breadcrumb-item><el-breadcrumb-item>{{ $t(pageInfo.titleKey) }}</el-breadcrumb-item></el-breadcrumb>
        </div>
        <div class="admin-topbar-actions">
          <el-dropdown trigger="click" @command="setLanguage">
            <button class="admin-language" aria-label="选择语言"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><circle cx="12" cy="12" r="9" /><ellipse cx="12" cy="12" rx="4" ry="9" /><path d="M3 12h18M5 6.5h14M5 17.5h14" /></svg><span>{{ currentLanguageLabel }}</span><el-icon><ArrowDown /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item v-for="language in languages" :key="language.value" :command="language.value" :disabled="language.value === locale">{{ language.label }}</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
          <el-dropdown trigger="click" @command="accountAction">
            <button class="admin-account"><span class="admin-avatar">{{ username.slice(0, 1).toUpperCase() }}</span><span class="admin-username">{{ username }}</span><el-icon><ArrowDown /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="password">{{ $t('header.changePassword') }}</el-dropdown-item><el-dropdown-item command="logout" divided>{{ $t('header.logout') }}</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>

      <nav ref="tabsElement" class="admin-tabs" aria-label="已打开的页面">
        <div v-for="tab in tabs" :key="tab.fullPath" class="admin-tab" :class="{ 'is-active': tab.fullPath === $route.fullPath }">
          <router-link :to="tab.fullPath" :aria-current="tab.fullPath === $route.fullPath ? 'page' : undefined">{{ $t(getPageInfo(tab.path).titleKey) }}</router-link>
          <button v-if="tab.path !== '/home'" class="admin-tab-close" :aria-label="`关闭${$t(getPageInfo(tab.path).titleKey)}页签`" @click="closeTab(tab.fullPath)"><el-icon><Close /></el-icon></button>
        </div>
      </nav>

      <main id="admin-main" class="admin-page-host" :class="{ 'is-workspace': $route.path === '/conversations' }"><slot /></main>
      <footer class="admin-footer">xiaozhi-esp32-server<span v-if="auth.pubConfig.version"> · {{ auth.pubConfig.version }}</span></footer>
    </div>
    <ChangePasswordDialog v-model="passwordVisible" />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowDown, Close, Expand, Fold, Setting } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import i18n, { changeLanguage } from '@/i18n';
import featureManager from '@/utils/featureManager';
import supportApi from '@/apis/module/support';
import { isSupportAdmin } from '@/utils/support.mjs';
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue';
import { getMenuGroups, getPageInfo } from './navigation';
import './admin-layout.scss';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const mobileQuery = window.matchMedia('(max-width: 960px)');
const mobile = ref(mobileQuery.matches);
const mobileOpen = ref(false);
let storedCollapse = false;
try { storedCollapse = localStorage.getItem('adminSidebarCollapsed') === 'true'; } catch { /* Storage can be unavailable. */ }
const collapsed = ref(storedCollapse);
const passwordVisible = ref(false);
const supportAvailable = ref(false);
const menuToggle = ref(null);
const tabsElement = ref(null);
const tabs = ref([{ path: '/home', fullPath: '/home' }]);
const username = computed(() => auth.userInfo.username || '账户');
const locale = computed(() => i18n.locale);
const languages = [
  { value: 'zh_CN', label: '简体中文' }, { value: 'zh_TW', label: '繁體中文' },
  { value: 'en', label: 'English' }, { value: 'de', label: 'Deutsch' },
  { value: 'vi', label: 'Tiếng Việt' }, { value: 'pt_BR', label: 'Português (Brasil)' },
];
const currentLanguageLabel = computed(() => languages.find(item => item.value === locale.value)?.label || '简体中文');
const conversationEnabled = import.meta.env.VITE_CONVERSATION_ENABLED === 'true';
const groups = computed(() => getMenuGroups({
  superAdmin: isSupportAdmin(auth.userInfo),
  features: auth.pubConfig.systemWebMenu?.features || {},
  conversationEnabled, supportAvailable: supportAvailable.value, supportAdmin: isSupportAdmin(auth.userInfo),
}));
const pageInfo = computed(() => getPageInfo(route.path));

watch(() => route.fullPath, fullPath => {
  if (!tabs.value.some(tab => tab.fullPath === fullPath)) {
    if (tabs.value.length >= 12) tabs.value.splice(1, 1);
    tabs.value.push({ path: route.path, fullPath });
  }
  mobileOpen.value = false;
  void nextTick(() => tabsElement.value?.querySelector('.admin-tab.is-active')?.scrollIntoView({ block: 'nearest', inline: 'nearest' }));
}, { immediate: true });

function toggleMenu() {
  if (mobile.value) mobileOpen.value = !mobileOpen.value;
  else {
    collapsed.value = !collapsed.value;
    try { localStorage.setItem('adminSidebarCollapsed', String(collapsed.value)); } catch { /* Optional preference. */ }
  }
}
function closeMobileMenu() { mobileOpen.value = false; }
async function navigate(path) {
  await router.push(path);
  if (mobile.value) { mobileOpen.value = false; await nextTick(); menuToggle.value?.focus(); }
}
function closeTab(fullPath) {
  const index = tabs.value.findIndex(tab => tab.fullPath === fullPath);
  if (index < 0 || tabs.value[index].path === '/home') return;
  tabs.value.splice(index, 1);
  if (route.fullPath === fullPath) void router.push(tabs.value[Math.max(0, index - 1)].fullPath);
}
function setLanguage(language) { changeLanguage(language); }
async function accountAction(action) {
  if (action === 'password') passwordVisible.value = true;
  else if (action === 'logout') await auth.logout();
}
function resize() { mobile.value = mobileQuery.matches; if (!mobile.value) mobileOpen.value = false; }
let disposed = false;
onMounted(() => {
  window.addEventListener('resize', resize, { passive: true });
  mobileQuery.addEventListener('change', resize);
  resize();
  void featureManager.waitForInitialization();
  if (conversationEnabled) supportApi.me().then(profile => { if (!disposed) supportAvailable.value = !!profile?.enabled; }).catch(() => {});
});
onBeforeUnmount(() => { disposed = true; window.removeEventListener('resize', resize); mobileQuery.removeEventListener('change', resize); });
</script>

<style>
/* The expert call workspace uses a compact rail without changing the user's
   sidebar preference or the shell on any other route. */
.admin-shell:has(.support-workspace) { --admin-sidebar-width: 84px; }
.admin-shell.is-collapsed:has(.support-workspace) { --admin-sidebar-width: 64px; }
.admin-shell:has(.support-workspace) .admin-topbar { height:44px; flex-basis:44px; padding-right:16px; }
.admin-shell:has(.support-workspace) .admin-tabs { height:32px; flex-basis:32px; }
.admin-shell:has(.support-workspace) .admin-tab a { padding-top:5px; padding-bottom:5px; font-size:12px; }
.admin-shell:has(.support-workspace) .admin-footer { display:none; }
.admin-shell:has(.support-workspace) .admin-page-host { padding:10px 12px; overflow:hidden; }
@media(min-width:961px) {
  .admin-shell:has(.support-workspace) .admin-sidebar { background:#123e3b; }
  .admin-shell:has(.support-workspace) .admin-brand { justify-content:center; height:58px; flex-basis:58px; padding:0; border-bottom-color:#315753; }
  .admin-shell:has(.support-workspace) .admin-brand strong,
  .admin-shell:has(.support-workspace) .admin-menu-item-group__title,
  .admin-shell:has(.support-workspace) .el-menu-item-group__title,
  .admin-shell:has(.support-workspace) .admin-sidebar-caption { display:none; }
  .admin-shell:not(.is-collapsed):has(.support-workspace) .admin-sidebar .el-menu-item,
  .admin-shell:not(.is-collapsed):has(.support-workspace) .admin-sidebar .el-sub-menu__title { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:7px; height:auto; min-height:74px; line-height:1.35; margin:6px; padding:10px 3px !important; white-space:normal; text-align:center; font-size:11px; border-radius:9px; }
  .admin-shell:has(.support-workspace) .admin-sidebar .el-menu .el-icon { font-size:23px; margin:0; }
  .admin-shell:has(.support-workspace) .admin-sidebar .el-menu-item.is-active { background:#087f7b; }
  .admin-shell:has(.support-workspace) .admin-sidebar .el-menu { --el-menu-hover-bg-color:#20554f; }
  .admin-shell:has(.support-workspace) .admin-sidebar .el-sub-menu__icon-arrow { right:3px; top:auto; bottom:3px; font-size:10px; }
}
@media(max-width:600px) {
  .admin-shell:has(.support-workspace) .admin-page-host { padding:6px; }
  .admin-shell:has(.support-workspace) .admin-topbar { height:46px; flex-basis:46px; }
}
</style>
