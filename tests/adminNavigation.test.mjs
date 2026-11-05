import { test } from 'vitest'
import assert from 'node:assert/strict'
import { getMenuGroups, getPageInfo, menuGroups } from '../src/layouts/navigation.js'
import zh from '../src/i18n/zh_CN.js'

test('filters permissions, features, support and empty groups', () => {
  const groups = getMenuGroups({ conversationEnabled: true })
  assert.deepEqual(groups.map(g => g.id), ['workspace'])
  assert.deepEqual(groups[0].items.map(i => i.path), ['/home', '/device-conversations'])
  assert.ok(getMenuGroups({ conversationEnabled: true, supportAdmin: true })[0].items.some(i => i.path === '/support'))
  assert.ok(!getMenuGroups({ supportAvailable: true })[0].items.some(i => i.path === '/support'))
  const all = getMenuGroups({ superAdmin: true, features: { voiceClone: true } })
  assert.ok(all.flatMap(g => g.items).some(i => i.path === '/voice-resource-management'))
  assert.ok(!all.flatMap(g => g.items).some(i => i.path === '/knowledge-base-management'))
})
test('context routes highlight their parent and titles exist', () => {
  for (const path of ['/role-config', '/device-management', '/voice-print']) assert.equal(getPageInfo(path).menuPath, '/home')
  assert.equal(getPageInfo('/template-quick-config').menuPath, '/agent-template-management')
  for (const path of [...menuGroups.flatMap(g => g.items.map(i => i.path)), '/role-config', '/device-management', '/voice-print', '/template-quick-config', '/', '/login', '/register', '/retrieve-password']) assert.ok(zh[getPageInfo(path).titleKey], path)
})

test('disabled feature objects remain hidden', () => {
  const paths = getMenuGroups({ features: { addressBook: { enabled: false }, knowledgeBase: { enabled: false }, voiceClone: { enabled: false } } }).flatMap(g => g.items.map(i => i.path))
  assert.deepEqual(paths, ['/home'])
  assert.ok(getMenuGroups({ features: { addressBook: { enabled: true } } })[0].items.some(i => i.path === '/address-book-management'))
})

test('shows keyword management under system settings for enabled internal sessions', () => {
  const path = '/esp32-intents'
  const groupFor = options => getMenuGroups(options).find(group => group.items.some(entry => entry.path === path))
  assert.equal(groupFor({}), undefined)
  assert.equal(groupFor({ esp32IntentsEnabled: true })?.id, 'settings')
  assert.equal(groupFor({ esp32IntentsEnabled: true, externalSession: true }), undefined)
  assert.equal(getPageInfo(path).menuPath, path)
  assert.equal(getPageInfo(path).sectionKey, 'adminLayout.settings')
})

test('consolidates settings and keeps operations in the workspace', () => {
  const groups = getMenuGroups({ superAdmin: true, conversationEnabled: true, features: { knowledgeBase: true, voiceClone: true } });
  assert.deepEqual(groups.map(group => group.id), ['workspace', 'settings']);
  const settings = groups.find(group => group.id === 'settings').items.map(entry => entry.path);
  for (const path of ['/model-config', '/knowledge-base-management', '/params-management', '/user-management', '/support-settings', '/feature-management']) assert.ok(settings.includes(path));
  assert.equal(getPageInfo('/support-settings').sectionKey, 'adminLayout.settings');
  assert.ok(groups[0].items.some(entry => entry.path === '/ai-operations'));
  assert.ok(!getMenuGroups({ conversationEnabled: true }).flatMap(group => group.items).some(entry => entry.path === '/support-settings'));
  assert.ok(!getMenuGroups({ superAdmin: true, conversationEnabled: true, externalSession: true }).flatMap(group => group.items).some(entry => entry.path === '/support-settings'));
});

test('shows each staff desk independently and removes internal routes for external sessions', () => {
  const paths = options => getMenuGroups({conversationEnabled:true,...options}).flatMap(group=>group.items.map(item=>item.path));
  assert.ok(paths({supportRoles:['support_agent']}).includes('/support'));
  assert.ok(!paths({supportRoles:['support_agent']}).includes('/expert-conversations'));
  assert.ok(paths({supportRoles:['expert']}).includes('/expert-conversations'));
  assert.ok(!paths({supportRoles:['expert']}).includes('/support'));
  assert.ok(!paths({supportRoles:['expert','support_agent']}).includes('/conversations'));
  assert.equal(paths({supportRoles:['expert','support_agent'],externalSession:true}).some(path=>['/support','/expert-conversations','/device-conversations'].includes(path)),false);
});
