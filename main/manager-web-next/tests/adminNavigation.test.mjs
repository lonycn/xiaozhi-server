import { test } from 'vitest'
import assert from 'node:assert/strict'
import { getMenuGroups, getPageInfo, menuGroups } from '../src/layouts/navigation.js'
import zh from '../src/i18n/zh_CN.js'

test('filters permissions, features, support and empty groups', () => {
  const groups = getMenuGroups({ conversationEnabled: true })
  assert.deepEqual(groups.map(g => g.id), ['workspace'])
  assert.deepEqual(groups[0].items.map(i => i.path), ['/home', '/conversations'])
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
