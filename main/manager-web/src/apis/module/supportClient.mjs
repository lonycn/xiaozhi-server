export function createSupportClient({ baseUrl, getToken, onUnauthorized, fetchImpl = globalThis.fetch, errorMessage = () => '请求未成功，请稍后重试' }) {
  async function request(path, method = 'GET', payload, signal) {
    const token = getToken() || '';
    const controller = new AbortController();
    const abort = () => controller.abort();
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
    const timeout = setTimeout(() => controller.abort(), 30000);
    const assertCurrent = () => { if (controller.signal.aborted || token !== (getToken() || '')) throw new DOMException('Aborted', 'AbortError'); };
    try {
      assertCurrent();
      const response = await fetchImpl(`${baseUrl.replace(/\/$/, '')}/api/v1/support${path}`, {
        method, signal: controller.signal,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        ...(payload === undefined ? {} : { body: JSON.stringify(payload) }),
      });
      assertCurrent();
      const body = await response.json().catch(() => null);
      assertCurrent();
      if (response.status === 401 || body?.code === 401) onUnauthorized();
      if (!response.ok || body?.code !== 0) {
        const status = response.status === 401 || body?.code === 401 ? 401 : Number.isInteger(body?.code) && body.code >= 400 && body.code <= 599 ? body.code : response.status;
        const error = new Error(errorMessage(body?.msg, status));
        error.code = body?.msg; error.status = status; throw error;
      }
      return body.data;
    } catch (error) {
      if (error.name === 'AbortError' && !signal?.aborted && token === (getToken() || '')) throw Object.assign(new Error(errorMessage('TIMEOUT')), { code: 'TIMEOUT' });
      if (error instanceof TypeError) throw Object.assign(new Error(errorMessage('NETWORK')), { code: 'NETWORK' });
      throw error;
    } finally { clearTimeout(timeout); signal?.removeEventListener('abort', abort); }
  }
  return {
    summaries: (id, before = '0', signal) => request(`/conversations/${encodeURIComponent(id)}/summary?before=${encodeURIComponent(before)}&limit=20`, 'GET', undefined, signal),
    prepareSummary: (id, signal) => request(`/conversations/${encodeURIComponent(id)}/summary/draft`, 'POST', {}, signal),
    editSummary: (id, payload, signal) => request(`/conversations/${encodeURIComponent(id)}/summary`, 'PUT', payload, signal),
    summaryAssistance: (id, signal) => request(`/conversations/${encodeURIComponent(id)}/summary/assistance`, 'GET', undefined, signal),
    createSummaryAssistance: (id, payload, signal) => request(`/conversations/${encodeURIComponent(id)}/summary/assistance`, 'POST', payload, signal),
    summaryAssistanceJob: (id, jobId, signal) => request(`/conversations/${encodeURIComponent(id)}/summary/assistance/${encodeURIComponent(jobId)}`, 'GET', undefined, signal),
    retrySummaryAssistance: (id, jobId, signal) => request(`/conversations/${encodeURIComponent(id)}/summary/assistance/${encodeURIComponent(jobId)}/retry`, 'POST', {}, signal),
    me: signal => request('/me', 'GET', undefined, signal),
    queue: (before = '0', serviceType, signal) => request(`/queue?before=${encodeURIComponent(before)}&limit=50${serviceType ? `&service_type=${encodeURIComponent(serviceType)}` : ''}`, 'GET', undefined, signal),
    assigned: (before = '0', serviceType, signal) => request(`/assigned?before=${encodeURIComponent(before)}&limit=50${serviceType ? `&service_type=${encodeURIComponent(serviceType)}` : ''}`, 'GET', undefined, signal),
    experts: (before = '0', signal) => request(`/experts?before=${encodeURIComponent(before)}&limit=50`, 'GET', undefined, signal),
    agents: (before = '0', signal) => request(`/agents?before=${encodeURIComponent(before)}&limit=50`, 'GET', undefined, signal),
    profile: (id, payload, signal) => request(`/agents/${encodeURIComponent(id)}`, 'PUT', payload, signal),
    presence: (accepting, serviceType, signal) => request('/presence', 'POST', { accepting, service_type: serviceType }, signal),
    get: (id, signal) => request(`/requests/${encodeURIComponent(id)}`, 'GET', undefined, signal),
    transfer: (id, payload, signal) => request(`/requests/${encodeURIComponent(id)}/transfer`, 'POST', payload, signal),
    action: (id, action) => request(`/requests/${encodeURIComponent(id)}/${action}`, 'POST', {}),
    send: (id, payload, signal) => request(`/requests/${encodeURIComponent(id)}/messages`, 'POST', payload, signal),
  };
}
