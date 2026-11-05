export function messagePreview(parts = []) {
  const text = parts.filter(part => part.type === 'text' && typeof part.text === 'string').map(part => part.text).join(' ').replace(/\s+/g, ' ').trim();
  const type = parts.find(part => ['image','video','audio','file','location'].includes(part.type))?.type || 'message';
  return text ? { type: 'text', text: text.slice(0, 120) } : { type, text: '' };
}
export function activityTime(request, activity = {}) {
  return activity[request.request_id]?.at || Date.parse(request.last_message_at || request.requested_at || '') || 0;
}
export function latestContacts(requests, activity = {}) {
  return [...requests].sort((a, b) => activityTime(b, activity) - activityTime(a, activity));
}
