export const isSupportAdmin = info => [true, 1, '1'].includes(info?.superAdmin);
export function cursorInteger(value) {
  if (typeof value === 'string' && /^[0-9]+$/.test(value)) return BigInt(value);
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return BigInt(value);
  throw new Error('历史记录游标异常，请刷新后重试');
}
export async function allPages(fetchPage, start = '0') {
  const items = []; let cursor = start;
  while (true) {
    const page = await fetchPage(cursor); items.push(...page.items);
    if (!page.has_more) return { items, cursor: page.next_cursor };
    if (cursorInteger(page.next_cursor) <= cursorInteger(cursor)) {
      throw new Error('历史记录游标异常，请刷新后重试');
    }
    cursor = page.next_cursor;
  }
}
export function senderKey(type) { return ({ user: 'user', support_agent: 'agent', expert: 'expert', assistant: 'ai', ai: 'ai', system: 'system' })[String(type).toLowerCase()] || 'unknown'; }

export const staffRoles = ['support_agent', 'expert'];
export function supportRoles(profile) {
  return [...new Set((Array.isArray(profile?.roles) ? profile.roles : [profile?.role]).filter(role => staffRoles.includes(role)))];
}
export function hasSupportRole(profile, role) { return profile?.enabled === true && supportRoles(profile).includes(role); }
export function supportAccepting(profile, role) {
  if (!hasSupportRole(profile, role)) return false;
  if (profile.service_presence) return profile.service_presence[role]?.accepting === true;
  // Legacy responses only describe the primary role, never every role on the account.
  return profile.role === role && profile.accepting === true;
}
export function supportDeskPath(role) { return role === 'expert' ? '/expert-conversations' : '/support'; }
