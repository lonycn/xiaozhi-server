import { createRouter, createWebHashHistory } from 'vue-router'
import { pinia, useAuthStore } from '../stores/auth'
import { anonymousRouteNames } from './authLifecycle'
import supportApi from '../apis/module/support'
import { isSupportAdmin, hasSupportRole, supportDeskPath } from '../utils/support.mjs'
import { ElMessage } from 'element-plus'
import i18n from '../i18n'

const routes = [
  { path: '/agent-chat-history', name: 'AgentChatHistory', component: () => import('../views/AgentChatHistory.vue') },
  { path: '/ai-operations', name: 'AiOperations', component: () => import('../views/AiOperations.vue'), meta: { superAdminOnly: true } },
  ...(import.meta.env.VITE_CONVERSATION_ENABLED === 'true' ? [{ path: '/support-settings', name: 'SupportSettings', component: () => import('../views/SupportSettings.vue'), meta: { superAdminOnly: true, deniedKey: 'support.settingsDenied' } }] : []),
  ...(import.meta.env.VITE_CONVERSATION_ENABLED === 'true' ? [
    { path: '/device-conversations', name: 'DeviceConversations', component: () => import('../views/DeviceConversations.vue'), meta: { internalOnly: true } },
    { path: '/support', name: 'SupportWorkspace', component: () => import('../views/SupportWorkspace.vue'), props: { serviceType: 'support_agent' }, meta: { internalOnly: true, staffRole: 'support_agent' } },
    { path: '/expert-conversations', name: 'ExpertConversations', component: () => import('../views/SupportWorkspace.vue'), props: { serviceType: 'expert' }, meta: { internalOnly: true, staffRole: 'expert' } },
  ] : []),
  ...(import.meta.env.VITE_CONVERSATION_ENABLED === 'true' ? [{
    path: '/conversations',
    name: 'ConversationWorkspace',
    component: () => import('../views/ConversationWorkspace.vue'),
  }] : []),
  {
    path: '/',
    name: 'welcome',
    component: function () {
      return import('../views/login.vue')
    }
  },
  {
    path: '/role-config',
    name: 'RoleConfig',
    component: function () {
      return import('../views/roleConfig.vue')
    }
  },
  ...(import.meta.env.VITE_ESP32_INTENTS_ENABLED === 'true' ? [
    { path: '/esp32-intents', name: 'Esp32Intents', component: () => import('../views/Esp32Intents.vue'), meta: { internalOnly: true } },
  ] : []),
  {
    path: '/voice-print',
    name: 'VoicePrint',
    component: function () {
      return import('../views/VoicePrint.vue')
    }
  },
  {
    path: '/login',
    name: 'login',
    component: function () {
      return import('../views/login.vue')
    }
  },
  {
    path: '/home',
    name: 'home',
    component: function () {
      return import('../views/home.vue')
    }
  },
  {
    path: '/register',
    name: 'Register',
    component: function () {
      return import('../views/register.vue')
    }
  },
  {
    path: '/retrieve-password',
    name: 'RetrievePassword',
    component: function () {
      return import('../views/retrievePassword.vue')
    }
  },
  // 设备管理页面路由
  {
    path: '/device-management',
    name: 'DeviceManagement',
    component: function () {
      return import('../views/DeviceManagement.vue')
    }
  },
  // 添加用户管理路由
  {
    path: '/user-management',
    name: 'UserManagement',
    component: function () {
      return import('../views/UserManagement.vue')
    }
  },
  {
    path: '/model-config',
    name: 'ModelConfig',
    component: function () {
      return import('../views/ModelConfig.vue')
    }
  },
  {
    path: '/params-management',
    name: 'ParamsManagement',
    component: function () {
      return import('../views/ParamsManagement.vue')
    }
  },
  {
    path: '/knowledge-base-management',
    name: 'KnowledgeBaseManagement',
    component: function () {
      return import('../views/KnowledgeBaseManagement.vue')
    }
  },
  {
    path: '/server-side-management',
    name: 'ServerSideManager',
    component: function () {
      return import('../views/ServerSideManager.vue')
    }
  },
  {
    path: '/ota-management',
    name: 'OtaManagement',
    component: function () {
      return import('../views/OtaManagement.vue')
    }
  },
  {
    path: '/voice-resource-management',
    name: 'VoiceResourceManagement',
    component: function () {
      return import('../views/VoiceResourceManagement.vue')
    }
  },
  {
    path: '/voice-clone-management',
    name: 'VoiceCloneManagement',
    component: function () {
      return import('../views/VoiceCloneManagement.vue')
    }
  },
  {
    path: '/dict-management',
    name: 'DictManagement',
    component: function () {
      return import('../views/DictManagement.vue')
    }
  },
  {
    path: '/provider-management',
    name: 'ProviderManagement',
    component: function () {
      return import('../views/ProviderManagement.vue')
    }
  },
  // 添加默认角色管理路由
  {
    path: '/agent-template-management',
    name: 'AgentTemplateManagement',
    component: function () {
      return import('../views/AgentTemplateManagement.vue')
    }
  },
  // 添加模板快速配置路由
  {
    path: '/template-quick-config',
    name: 'TemplateQuickConfig',
    component: function () {
      return import('../views/TemplateQuickConfig.vue')
    }
  },
  // 功能配置页面路由
  {
    path: '/feature-management',
    name: 'FeatureManagement',
    component: function () {
      return import('../views/FeatureManagement.vue')
    }
  },
  // 替换词管理
  {
    path: '/replacement-word-management',
    name: 'ReplacementWordManagement',
    component: function () {
      return import('../views/ReplacementWordManagement.vue')
    }
  },
  // 通讯录管理页面路由
  {
    path: '/address-book-management',
    name: 'AddressBookManagement',
    component: function () {
      return import('../views/AddressBookManagement.vue')
    }
  },
]
const router = createRouter({ history: createWebHashHistory(import.meta.env.BASE_URL), routes })

// 匿名可访问的路由白名单
const anonRouteNames = anonymousRouteNames
const deviceHistoryOnly = import.meta.env.VITE_DEVICE_HISTORY_ONLY === 'true'

// 路由守卫
router.beforeEach(async (to, from, next) => {
  const token = useAuthStore(pinia).bearer;
  const isAnonRoute = anonRouteNames.has(to.name);

  if (deviceHistoryOnly) {
    if (/^xext_/i.test(token)) {
      useAuthStore(pinia).clearAuth();
      next({ name: 'login', query: to.query, replace: true });
      return;
    }
    if (!token && to.name !== 'login') {
      next({ name: 'login', query: to.query, replace: true });
      return;
    }
    if (token && to.name !== 'DeviceConversations') {
      next({ name: 'DeviceConversations', query: to.query, replace: true });
      return;
    }
    next();
    return;
  }

  if (isAnonRoute && token) {
    next({ name: 'home', replace: true })
    return
  }

  if (!isAnonRoute && !token) {
    next({
      name: 'login',
      replace: true
    })
    return
  }

  if (to.path === '/conversations' && to.query.source === 'hardware') { next({ path: '/device-conversations', query: to.query, replace: true }); return }

  if (to.meta.internalOnly && /^xext_/i.test(token)) {
    ElMessage.warning(i18n.t('support.denied')); next({ name: 'home', replace: true }); return
  }
  if (to.meta.staffRole) {
    try {
      const profile = await supportApi.me();
      if (token !== useAuthStore(pinia).bearer) { next(false); return }
      if (to.path === '/support' && typeof to.query.request === 'string') {
        try {
          const request = await supportApi.get(to.query.request);
          if (token !== useAuthStore(pinia).bearer) { next(false); return }
          if (request?.service_type === 'expert' && (hasSupportRole(profile, 'expert') || isSupportAdmin(useAuthStore(pinia).userInfo))) { next({ path: supportDeskPath('expert'), query: to.query, replace: true }); return }
        } catch { /* The desk presents resource-level errors without opening another request. */ }
      }
      if (!hasSupportRole(profile, to.meta.staffRole) && !isSupportAdmin(useAuthStore(pinia).userInfo)) { ElMessage.warning(i18n.t('support.denied')); next({ name: 'home', replace: true }); return }
    } catch { /* Display the backend error in the desk when profile discovery fails. */ }
  }

  if (to.meta.superAdminOnly && (!isSupportAdmin(useAuthStore(pinia).userInfo) || /^xext_/i.test(token))) {
    ElMessage.warning(i18n.t(to.meta.deniedKey || 'aiOperations.forbidden'))
    next({ name: 'home', replace: true })
    return
  }

  next()
})

export default router
