<template>
  <div class="admin-shell" :class="{ 'is-collapsed': collapsed, 'is-mobile': mobile, 'menu-open': mobileOpen }" @keydown.esc="closeMobileMenu">
    <button v-if="mobile && mobileOpen" class="admin-menu-mask" aria-label="关闭导航菜单" @click="closeMobileMenu" />
    <aside id="admin-sidebar" v-show="!mobile || mobileOpen" class="admin-sidebar" :inert="mobile && !mobileOpen" :aria-hidden="mobile && !mobileOpen" aria-label="主导航">
      <button v-if="mobile && mobileOpen" class="admin-mobile-menu-close admin-icon-button" aria-label="关闭导航菜单" @click="closeMobileMenu"><el-icon><Close /></el-icon></button>
      <router-link to="/home" class="admin-brand" @click="closeMobileMenu">
        <img src="@/assets/xiaozhi-logo.png" alt="" />
        <strong v-if="!collapsed || mobile">智控台</strong>
      </router-link>
      <el-scrollbar class="admin-menu-scroll">
        <el-menu :default-active="pageInfo.menuPath" :collapse="collapsed && !mobile" :collapse-transition="false" background-color="transparent" text-color="#b9c5d6" active-text-color="#fff" @select="navigate">
          <template v-for="group in groups" :key="group.id">
            <el-sub-menu v-if="group.id === 'settings'" :index="group.id">
              <template #title><el-icon><Setting /></el-icon><span>{{ $t(group.labelKey) }}</span></template>
              <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path"><el-icon><component :is="item.icon" /></el-icon><template #title>{{ $t(item.titleKey) }}<span v-if="deskCount(item.path)" class="admin-desk-badge">{{ deskCount(item.path) > 99 ? '99+' : deskCount(item.path) }}</span></template></el-menu-item>
            </el-sub-menu>
            <el-menu-item-group v-else>
              <template #title><span v-if="!collapsed || mobile">{{ $t(group.labelKey) }}</span></template>
              <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path"><el-icon><component :is="item.icon" /></el-icon><template #title>{{ $t(item.titleKey) }}<span v-if="deskCount(item.path)" class="admin-desk-badge">{{ deskCount(item.path) > 99 ? '99+' : deskCount(item.path) }}</span></template></el-menu-item>
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
          <div v-if="!deviceHistoryOnly && supportProfile?.enabled" class="admin-staff-alerts"><span v-if="staffSession.state.error" class="admin-support-error" role="status" :aria-label="$t('supportNotifications.connectionError')" :title="staffSession.state.error">!</span>
            <button type="button" class="admin-notifications" :class="{ 'has-unread': notificationCount }" :aria-label="$t('supportNotifications.pendingCount', { count: notificationCount })" @click="openNotifications"><el-icon><Bell /></el-icon><span v-if="notificationCount" class="admin-notification-count">{{ notificationCount > 99 ? '99+' : notificationCount }}</span></button>
            <button type="button" class="admin-sound" :aria-label="soundLabel" :aria-pressed="staffSession.state.soundEnabled && staffSession.state.soundReady" :disabled="!staffSession.state.soundAvailable" @click="toggleSound"><el-icon><BellFilled v-if="staffSession.state.soundEnabled && staffSession.state.soundReady" /><MuteNotification v-else /></el-icon><span>{{ soundLabel }}</span></button>
          </div>
          <el-dropdown trigger="click" @command="setLanguage">
            <button class="admin-language" aria-label="选择语言"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true"><circle cx="12" cy="12" r="9" /><ellipse cx="12" cy="12" rx="4" ry="9" /><path d="M3 12h18M5 6.5h14M5 17.5h14" /></svg><span>{{ currentLanguageLabel }}</span><el-icon><ArrowDown /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item v-for="language in languages" :key="language.value" :command="language.value" :disabled="language.value === locale">{{ language.label }}</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
          <el-dropdown trigger="click" @command="accountAction">
            <button class="admin-account"><span class="admin-avatar">{{ username.slice(0, 1).toUpperCase() }}</span><span class="admin-username">{{ username }}</span><el-icon><ArrowDown /></el-icon></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item v-if="!deviceHistoryOnly" command="password">{{ $t('header.changePassword') }}</el-dropdown-item><el-dropdown-item command="logout" :divided="!deviceHistoryOnly">{{ $t('header.logout') }}</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </header>

      <nav ref="tabsElement" class="admin-tabs" aria-label="已打开的页面">
        <div v-for="tab in tabs" :key="tab.fullPath" class="admin-tab" :class="{ 'is-active': tab.fullPath === $route.fullPath }">
          <router-link :to="tab.fullPath" :aria-current="tab.fullPath === $route.fullPath ? 'page' : undefined">{{ $t(getPageInfo(tab.path).titleKey) }}</router-link>
          <button v-if="tab.path !== '/home'" class="admin-tab-close" :aria-label="`关闭${$t(getPageInfo(tab.path).titleKey)}页签`" @click="closeTab(tab.fullPath)"><el-icon><Close /></el-icon></button>
        </div>
      </nav>

      <main id="admin-main" class="admin-page-host" :class="{ 'is-workspace': ['/conversations', '/device-conversations', '/support', '/expert-conversations', '/agent-chat-history'].includes($route.path) }"><slot /></main>
      <footer class="admin-footer">xiaozhi-esp32-server<span v-if="deviceHistoryOnly"> · 实机服务</span><span v-if="auth.pubConfig.version"> · {{ auth.pubConfig.version }}</span></footer>
    </div>
    <ChangePasswordDialog v-if="!deviceHistoryOnly" v-model="passwordVisible" />
  </div>
</template>

<script setup>
import { h, computed, nextTick, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowDown, Bell, BellFilled, Phone, VideoCamera, MuteNotification, Close, Expand, Fold, Setting } from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import i18n, { changeLanguage } from '@/i18n';
import featureManager from '@/utils/featureManager';
import { ElNotification } from 'element-plus';
import { createSupportSession } from '@/support/deskSession.mjs';
import { createStaffSound } from '@/support/sound.mjs';
import conversationApi from '@/apis/module/conversation';
import supportApi from '@/apis/module/support';
import mediaApi from '@/apis/module/media';
import { isSupportAdmin, supportRoles, supportDeskPath } from '@/utils/support.mjs';
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue';
import { getMenuGroups, getPageInfo } from './navigation';
import './admin-layout.scss';
import '@/support/notifications.scss';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const deviceHistoryOnly = import.meta.env.VITE_DEVICE_HISTORY_ONLY === 'true';
const staffSession = createSupportSession({
  api: supportApi, conversations: conversationApi, media: mediaApi, sound: createStaffSound(), locks: navigator.locks,
  storage: { getItem: key => localStorage.getItem(key), setItem: (key, value) => localStorage.setItem(key, value) },
  isVisible: () => document.visibilityState === 'visible' && document.hasFocus(),
  notify: event => {
    const identity = supportIdentity.value;
    const call = event.kind === 'call';
    const desk = i18n.t(event.service_type === 'expert' ? 'conversationManagement.experts' : 'conversationManagement.support');
    const open = () => {
      if (identity !== supportIdentity.value || !supportRoles(staffSession.state.profile).includes(event.service_type)) return;
      if (!call) notice.close();
      openNotice(event);
    };
    const preview = event.preview?.type === 'text' ? event.preview.text : event.preview ? i18n.t('supportNotifications.preview_' + event.preview.type) : '';
    const notice = ElNotification({
      title: desk + ' · ' + i18n.t(call ? (event.mode === 'video' ? 'supportNotifications.videoCall' : 'supportNotifications.audioCall') : event.kind === 'message' ? 'supportNotifications.messageTitle' : 'supportNotifications.requestTitle'),
      message: h('button', { type: 'button', class: 'staff-toast__open', onClick: click => { click.stopPropagation(); open(); } }, [
        h('strong', event.title || i18n.t(call ? 'supportNotifications.callSubject' : 'support.consultation')),
        h('span', { class: 'staff-toast__preview' }, call ? i18n.t('supportNotifications.callHint') : preview || i18n.t('supportNotifications.' + event.kind, { desk, count: event.unread_count || event.count || 1 })),
        h('span', { class: 'staff-toast__hint' }, i18n.t(call ? 'supportNotifications.callAction' : 'supportNotifications.openMessage')),
      ]),
      customClass: 'staff-toast' + (call ? ' staff-toast--call' : ''),
      type: call ? 'warning' : 'info', icon: call ? (event.mode === 'video' ? VideoCamera : Phone) : Bell, offset: 72, duration: call ? 0 : 8000,
      onClick: open, onClose: () => { if (call) staffSession.silenceCall(event.call_key); },
    });
    return () => notice.close();
  },
});
provide('supportSession', deviceHistoryOnly ? null : staffSession);
const notificationCounts = computed(() => staffSession.counts());
const notificationCount = computed(() => notificationCounts.value.support_agent + notificationCounts.value.expert);
const soundLabel = computed(() => i18n.t(!staffSession.state.soundAvailable ? 'supportNotifications.soundUnavailable' : !staffSession.state.soundEnabled ? 'supportNotifications.soundOff' : staffSession.state.soundReady ? 'supportNotifications.soundOn' : 'supportNotifications.soundEnable'));
const supportProfile = ref(null);
const baseTitle = document.title;
watch(notificationCount, count => { document.title = count ? '(' + count + ') ' + baseTitle : baseTitle; });
watch(() => staffSession.state.profile, profile => { if (!deviceHistoryOnly) supportProfile.value = profile; }, { flush: 'sync' });
function deskCount(path) { return path === '/support' ? notificationCounts.value.support_agent : path === '/expert-conversations' ? notificationCounts.value.expert : 0; }
function openNotice(event) {
  if (event.kind === 'call') {
    void router.push({ path: supportDeskPath(event.service_type), query: { media_session: event.session_id, ...(event.type === 'session' && event.request_id ? { request: event.request_id } : {}) } }).catch(() => {});
    return;
  }
  if (event.kind !== 'queue' && staffSession.reveal?.(event.request_id)) return;
  if (event.kind === 'queue' && event.request_id) staffSession.markRead(event.request_id);
  void router.push({ path: supportDeskPath(event.service_type), query: event.kind === 'queue' ? {} : { request: event.request_id, ...(event.kind === 'message' ? { focus: 'message' } : {}) } }).catch(() => {});
}
function openNotifications() { openNotice(staffSession.nextNotice() || { kind: 'queue', service_type: supportRoles(supportProfile.value)[0] }); }
async function toggleSound() {
  if (!staffSession.state.soundEnabled) staffSession.setSoundEnabled(true);
  else if (staffSession.state.soundReady) { staffSession.setSoundEnabled(false); return; }
  await staffSession.unlockSound();
}
function syncSoundPreference(event) { if (event.key?.startsWith('staff-notification-sound:')) staffSession.syncSoundPreference(); }
function unlockSound(event) {
  if (event.target?.closest?.('.admin-sound')) return;
  if (staffSession.state.soundEnabled) void staffSession.unlockSound();
}

const mobileQuery = window.matchMedia('(max-width: 960px)');
const mobile = ref(mobileQuery.matches);
const mobileOpen = ref(false);
let storedCollapse = false;
try { storedCollapse = localStorage.getItem('adminSidebarCollapsed') === 'true'; } catch { /* Storage can be unavailable. */ }
const collapsed = ref(storedCollapse);
const passwordVisible = ref(false);

let supportController = null;
const supportIdentity = computed(() => JSON.stringify([auth.bearer, auth.userInfo.id, auth.userInfo.superAdmin]));
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
  externalSession: /^xext_/i.test(auth.bearer),
  features: auth.pubConfig.systemWebMenu?.features || {},
  esp32IntentsEnabled: import.meta.env.VITE_ESP32_INTENTS_ENABLED === 'true',
  conversationEnabled, supportRoles: supportRoles(supportProfile.value), supportAdmin: isSupportAdmin(auth.userInfo),
}));
const pageInfo = computed(() => getPageInfo(route.path));

watch(() => route.fullPath, fullPath => {
  if (route.name === 'Esp32Intents') {
    const existing = tabs.value.find(tab => tab.path === route.path);
    if (existing) {
      existing.fullPath = fullPath;
      return;
    }
  }
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
function resize() {
  const nextMobile = mobileQuery.matches;
  if (nextMobile !== mobile.value || !nextMobile) mobileOpen.value = false;
  mobile.value = nextMobile;
}
let disposed = false;
onMounted(() => {
  window.addEventListener('resize', resize, { passive: true });
  window.addEventListener('storage', syncSoundPreference);
  mobileQuery.addEventListener('change', resize);
  resize();
  document.addEventListener('pointerdown', unlockSound, { passive: true });
  document.addEventListener('keydown', unlockSound);
  void featureManager.waitForInitialization();

});
watch(supportIdentity, async key => {
  supportController?.abort(); staffSession.stop(); supportProfile.value = null;
  tabs.value = tabs.value.filter(tab => !['/support', '/expert-conversations', '/support-settings'].includes(tab.path));
  if (!conversationEnabled || !auth.bearer || /^xext_/i.test(auth.bearer)) return;
  if (!deviceHistoryOnly) { await staffSession.start(key); return; }
  const controller = new AbortController(); supportController = controller;
  try { const profile = await supportApi.me(controller.signal); if (!disposed && !controller.signal.aborted && key === supportIdentity.value && profile?.enabled) supportProfile.value = profile; } catch { /* Backend authorization remains authoritative. */ }
}, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { disposed = true; supportController?.abort(); staffSession.stop(); document.title = baseTitle; document.removeEventListener('pointerdown', unlockSound); document.removeEventListener('keydown', unlockSound); window.removeEventListener('resize', resize); window.removeEventListener('storage', syncSoundPreference); mobileQuery.removeEventListener('change', resize); });
</script>

<style scoped>
.admin-support-error{color:var(--el-color-warning);font-weight:700}.admin-staff-alerts{display:flex;align-items:center;gap:8px}.admin-notifications{position:relative;display:grid;place-items:center;width:32px;height:32px;border:0;border-radius:5px;background:transparent;color:#34465d;font-size:var(--admin-font-title);cursor:pointer}.admin-notifications:hover,.admin-sound:hover{background:var(--el-fill-color-light)}.admin-notification-count,.admin-desk-badge{min-width:20px;height:20px;line-height:20px;padding:0 4px;border-radius:10px;background:var(--el-color-danger);color:white;font-size:var(--admin-font-xs);text-align:center}.admin-notification-count{position:absolute;top:-2px;right:-5px}.admin-desk-badge{margin-left:8px;display:inline-block;vertical-align:middle}.admin-sound{display:flex;align-items:center;gap:5px;border:0;border-radius:5px;background:transparent;color:#60718b;padding:7px 5px;font:inherit;font-size:var(--admin-font-xs);cursor:pointer;white-space:nowrap}.admin-sound[aria-pressed="true"]{color:var(--el-color-primary)}.admin-sound>.el-icon{font-size:var(--admin-font-md)}.admin-sound:disabled{opacity:.5;cursor:not-allowed}
@media(max-width:960px){.admin-staff-alerts{gap:3px}.admin-sound span{display:none}.admin-sound{padding:7px 4px}}
.admin-mobile-menu-close { position:absolute; top:13px; right:10px; z-index:1; color:inherit; }
@media (max-width:960px) {
  /* Close immediately at the breakpoint; resizing must not animate a closed drawer over content. */
  .admin-shell:not(.menu-open) .admin-sidebar { display:none; }
}
</style>
