/** Agent history requests are bounded and fenced to the initiating identity. */
export function createAgentHistoryClient({ baseUrl, getToken, fetchImpl = globalThis.fetch, onUnauthorized = () => {} }) {
  const base = baseUrl.replace(/\/$/, '');
  const part = value => encodeURIComponent(value);
  const query = params => {
    const values = new URLSearchParams();
    for (const [key, value] of Object.entries(params || {})) if (value != null && value !== '') values.set(key, value);
    return values.size ? `?${values}` : '';
  };
  async function request(path, { signal, method = 'GET', blob = false } = {}) {
    const token = getToken();
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
    const timeout = setTimeout(abort, 15000);
    const fence = () => {
      if (controller.signal.aborted) throw new DOMException('Aborted', 'AbortError');
      if (getToken() !== token) throw Object.assign(new Error('STALE_IDENTITY'), { code: 'STALE_IDENTITY' });
    };
    try {
      const response = await fetchImpl(`${base}${path}`, {
        method, signal: controller.signal, headers: { Authorization: `Bearer ${token || ''}` }, cache: 'no-store',
      });
      fence();
      const binary = blob && response.ok && !response.headers?.get('content-type')?.includes('application/json');
      const body = binary ? await response.blob() : await response.json().catch(() => null);
      fence();
      if (response.status === 401 || body?.code === 401) onUnauthorized();
      if (!response.ok || (blob ? !binary : body?.code !== 0)) throw Object.assign(new Error('HISTORY_UNAVAILABLE'), { status: response.ok ? body?.code : response.status });
      return blob ? body : body.data;
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener('abort', abort);
    }
  }
  return {
    devices: (agentId, signal) => request(`/agent/${part(agentId)}/history-devices`, { signal }),
    sessions: (agentId, params, signal) => request(`/agent/${part(agentId)}/sessions${query(params)}`, { signal }),
    messages: (agentId, sessionId, macAddress, signal) => request(`/agent/${part(agentId)}/chat-history/${part(sessionId)}${query({ macAddress })}`, { signal }),
    audioGrant: (audioId, signal) => request(`/agent/audio/${part(audioId)}`, { method: 'POST', signal }),
    audioUrl: grant => `${base}/agent/play/${part(grant)}`,
    async download(agentId, sessionId, macAddress, previous, signal) {
      const grant = await request(`/agent/chat-history/getDownloadUrl/${part(agentId)}/${part(sessionId)}${query({ macAddress })}`, { method: 'POST', signal });
      return request(`/agent/chat-history/download/${part(grant)}/${previous ? 'previous' : 'current'}`, { blob: true, signal });
    },
  };
}
