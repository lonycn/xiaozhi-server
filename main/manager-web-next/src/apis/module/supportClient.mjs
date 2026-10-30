export function createSupportClient({ baseUrl, getToken, onUnauthorized, fetchImpl = globalThis.fetch, errorMessage = () => '请求未成功，请稍后重试' }) {
  async function request(path, method = 'GET', payload, signal) {
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
    const timeout = setTimeout(() => controller.abort(), 30000);
    try {
      const response = await fetchImpl(`${baseUrl.replace(/\/$/, '')}/api/v1/support${path}`, {
        method, signal: controller.signal,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken() || ''}` },
        ...(payload === undefined ? {} : { body: JSON.stringify(payload) }),
      });
      const body = await response.json().catch(() => null);
      if (response.status === 401 || body?.code === 401) onUnauthorized();
      if (!response.ok || body?.code !== 0) {
        const status = response.status === 401 || body?.code === 401 ? 401 : response.status;
        const error = new Error(errorMessage(body?.msg, status));
        error.code = body?.msg; error.status = status; throw error;
      }
      return body.data;
    } catch (error) {
      if (error.name === 'AbortError' && !signal?.aborted) throw Object.assign(new Error(errorMessage('TIMEOUT')), { code: 'TIMEOUT' });
      if (error instanceof TypeError) throw Object.assign(new Error(errorMessage('NETWORK')), { code: 'NETWORK' });
      throw error;
    } finally { clearTimeout(timeout); signal?.removeEventListener('abort', abort); }
  }
  return {
    summaries: (id, before = '0', signal) => request(`/conversations/${encodeURIComponent(id)}/summary?before=${encodeURIComponent(before)}&limit=20`, 'GET', undefined, signal),
    prepareSummary: (id, signal) => request(`/conversations/${encodeURIComponent(id)}/summary/draft`, 'POST', {}, signal),
    editSummary: (id, payload, signal) => request(`/conversations/${encodeURIComponent(id)}/summary`, 'PUT', payload, signal),
    me: () => request('/me'),
    queue: (before = '0') => request(`/queue?before=${encodeURIComponent(before)}&limit=50`),
    assigned: (before = '0') => request(`/assigned?before=${encodeURIComponent(before)}&limit=50`),
    agents: (before = '0') => request(`/agents?before=${encodeURIComponent(before)}&limit=50`),
    profile: (id, payload) => request(`/agents/${encodeURIComponent(id)}`, 'PUT', payload),
    presence: accepting => request('/presence', 'POST', { accepting }),
    get: id => request(`/requests/${encodeURIComponent(id)}`),
    action: (id, action) => request(`/requests/${encodeURIComponent(id)}/${action}`, 'POST', {}),
    send: (id, payload, signal) => request(`/requests/${encodeURIComponent(id)}/messages`, 'POST', payload, signal),
  };
}
