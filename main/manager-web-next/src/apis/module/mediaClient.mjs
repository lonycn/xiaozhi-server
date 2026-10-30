/** B5 bearer APIs. Room names, identities and grants are exclusively server owned. */
export function createMediaClient({ baseUrl, getToken, fetchImpl = globalThis.fetch, onUnauthorized = () => {} }) {
  const root = `${baseUrl.replace(/\/$/, '')}/api/v1/support`;
  const key = value => encodeURIComponent(String(value));
  async function request(path, payload, signal) {
    const controller = new AbortController(), abort = () => controller.abort();
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
    const timer = setTimeout(abort, 15000);
    try {
      const response = await fetchImpl(`${root}${path}`, { method: payload === undefined ? 'GET' : 'POST', signal: controller.signal,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken() || ''}` },
        ...(payload === undefined ? {} : { body: JSON.stringify(payload) }) });
      const body = await response.json().catch(() => null);
      if (response.status === 401 || body?.code === 401) onUnauthorized();
      if (!response.ok || body?.code !== 0) throw Object.assign(new Error('MEDIA_REQUEST_FAILED'), { status: response.status, code: body?.msg || 'MEDIA_REQUEST_FAILED' });
      return body.data;
    } catch (error) {
      if (error.name === 'AbortError' && !signal?.aborted) throw Object.assign(new Error('TIMEOUT'), { code: 'TIMEOUT' });
      throw error;
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
  }
  const action = (id, name, body, signal) => request(`/media-sessions/${key(id)}/${name}`, body, signal);
  return {
    current: (conversationId, signal) => request(`/media-sessions/current?conversation_id=${key(conversationId)}`, undefined, signal),
    invitations: (after = '0', signal) => request(`/media-invitations?after=${key(after)}&limit=50`, undefined, signal),
    experts: (after = '0', signal) => request(`/media-experts?after=${key(after)}&limit=50`, undefined, signal),
    get: (id, signal) => request(`/media-sessions/${key(id)}`, undefined, signal),
    create: (body, signal) => request('/media-sessions', body, signal),
    accept: (id, body, signal) => action(id, 'accept', body, signal),
    reject: (id, body, signal) => action(id, 'reject', body, signal),
    token: (id, body, signal) => action(id, 'token', body, signal),
    ready: (id, body, signal) => action(id, 'ready', body, signal),
    end: (id, body, signal) => action(id, 'end', body, signal),
    transfer: (id, body, signal) => action(id, 'transfers', body, signal),
    transferGet: (id, signal) => request(`/media-transfers/${key(id)}`, undefined, signal),
    transferAction: (id, name, body, signal) => {
      if (!['approve','accept','reject','cancel'].includes(name)) throw new Error('INVALID_MEDIA_ACTION');
      return request(`/media-transfers/${key(id)}/${name}`, body, signal);
    },
    capture: (id, body, signal) => action(id, 'frames', body, signal),
  };
}
