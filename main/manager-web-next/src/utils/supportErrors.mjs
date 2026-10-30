const groups = {
  permission: ['SUPPORT_PERMISSION_REQUIRED', 'ENABLED_SUPPORT_ACCOUNT_REQUIRED', 'ENABLED_SUPPORT_PROFILE_REQUIRED', 'CURRENT_ASSIGNMENT_REQUIRED', 'SUPPORT_ADMIN_REQUIRED', 'OWNER_CANNOT_CLAIM', 'TARGET_AGENT_REQUIRED'],
  capacity: ['AGENT_AT_CAPACITY'],
  unavailable: ['AGENT_NOT_ACCEPTING'],
  changed: ['REQUEST_NOT_QUEUED', 'REQUEST_NOT_ACTIVE', 'ASSIGNMENT_CHANGED', 'ONLY_QUEUED_REQUEST_CAN_CANCEL'],
  activeRequest: ['ACTIVE_SUPPORT_REQUEST_EXISTS'],
  missing: ['SUPPORT_REQUEST_NOT_FOUND', 'CONVERSATION_NOT_FOUND', 'RESOURCE_NOT_FOUND'],
  invalidProfile: ['INVALID_AGENT_PROFILE', 'ENABLED_USER_NOT_FOUND'],
  conflict: ['CLIENT_REQUEST_ID_REUSED', 'CLIENT_MESSAGE_ID_REUSED'],
};
export function supportErrorKey(code, status) {
  if (status === 401) return 'support.errorAuth';
  if (code === 'TIMEOUT') return 'support.errorTimeout';
  for (const [key, codes] of Object.entries(groups)) if (codes.includes(code)) return `support.error${key[0].toUpperCase()}${key.slice(1)}`;
  if (status === 403) return 'support.errorPermission';
  if (status === 404) return 'support.errorMissing';
  return 'support.errorGeneric';
}
