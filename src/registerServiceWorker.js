// Candidate builds intentionally do not install a service worker. Never unregister
// other scopes or delete origin-wide caches: the old frontend may still use them.
export function register() { return false; }
