const item = (path, titleKey, icon, extra = {}) => ({ path, titleKey, icon, ...extra })
export const menuGroups = [
  { id: 'workspace', label: '工作空间', labelKey: 'adminLayout.workspace', icon: 'Monitor', items: [
    item('/home', 'header.smartManagement', 'Monitor'),
    item('/conversations', 'adminLayout.conversations', 'ChatDotRound', { conversationOnly: true }),
    item('/support', 'support.workspace', 'Service', { conversationOnly: true, supportOnly: true }),
    item('/address-book-management', 'header.addressBook', 'User', { feature: 'addressBook' }),
  ] },
  { id: 'models', label: '模型与知识', labelKey: 'adminLayout.models', icon: 'Collection', items: [
    item('/model-config', 'header.modelConfig', 'Setting', { adminOnly: true }),
    item('/knowledge-base-management', 'header.knowledgeBase', 'Collection', { feature: 'knowledgeBase' }),
    item('/voice-clone-management', 'header.voiceCloneManagement', 'Microphone', { feature: 'voiceClone' }),
    item('/voice-resource-management', 'header.voiceResourceManagement', 'Headset', { adminOnly: true, feature: 'voiceClone' }),
  ] },
  { id: 'system', label: '系统管理', labelKey: 'adminLayout.system', icon: 'Setting', items: [
    item('/params-management', 'header.paramManagement', 'Setting', { adminOnly: true }),
    item('/user-management', 'header.userManagement', 'User', { adminOnly: true }),
    item('/ota-management', 'header.otaManagement', 'Upload', { adminOnly: true }),
    item('/dict-management', 'header.dictManagement', 'Document', { adminOnly: true }),
    item('/provider-management', 'header.providerManagement', 'Grid', { adminOnly: true }),
    item('/agent-template-management', 'header.agentTemplate', 'Files', { adminOnly: true }),
    item('/replacement-word-management', 'header.replacementWordManagement', 'Edit', { adminOnly: true }),
    item('/server-side-management', 'header.serverSideManagement', 'Monitor', { adminOnly: true }),
    item('/feature-management', 'header.featureManagement', 'Setting', { adminOnly: true }),
  ] },
]
export function getMenuGroups({ superAdmin = false, features = {}, conversationEnabled = false, supportAvailable = false, supportAdmin = false } = {}) {
  return menuGroups.map(group => ({ ...group, items: group.items.filter(entry =>
    (!entry.adminOnly || superAdmin) && (!entry.feature || features[entry.feature] === true || features[entry.feature]?.enabled === true) &&
    (!entry.conversationOnly || conversationEnabled) && (!entry.supportOnly || supportAvailable || supportAdmin)
  ).map(entry => ({ ...entry })) })).filter(group => group.items.length)
}
const contextual = {
  '/role-config': ['roleConfig.title', '/home'],
  '/device-management': ['device.management', '/home'],
  '/voice-print': ['voicePrint.pageTitle', '/home'],
  '/template-quick-config': ['templateQuickConfig.title', '/agent-template-management'],
  '/': ['login.title', null], '/login': ['login.title', null],
  '/register': ['register.title', null], '/retrieve-password': ['retrievePassword.subtitle', null],
}
export function getPageInfo(path) {
  const [override, menuPath] = contextual[path] || [null, path]
  const group = menuGroups.find(g => g.items.some(i => i.path === menuPath))
  const entry = group?.items.find(i => i.path === menuPath)
  return { titleKey: override || entry?.titleKey || 'header.smartManagement', section: group?.label || '', sectionKey: group?.labelKey || '', menuPath: menuPath || null }
}
