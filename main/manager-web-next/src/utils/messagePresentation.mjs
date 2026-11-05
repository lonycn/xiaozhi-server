const roles = new Set(['user', 'assistant', 'support_agent', 'expert', 'tool', 'system']);

export function messageRole(type) {
  const role = String(type || '').toLowerCase();
  return role === 'ai' ? 'assistant' : roles.has(role) ? role : 'unknown';
}

export function isOwnMessage(message, currentUserId) {
  const role = messageRole(message?.sender_type);
  if (!['user', 'support_agent', 'expert'].includes(role)) return false;
  const sender = message?.sender_id;
  if (sender == null || currentUserId == null || sender === '' || currentUserId === '') return false;
  return String(sender) === String(currentUserId);
}
