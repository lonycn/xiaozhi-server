/** Owner-authenticated device input. Never persist transcripts or transport credentials. */
export function createDeviceDebugClient({ baseUrl, getToken, fetchImpl = globalThis.fetch }) {
  async function request(path, { signal, payload } = {}) {
    const token = getToken(), controller = new AbortController();
    const abort = () => controller.abort();
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
    const timeout = setTimeout(abort, 12000);
    const current = () => { if (controller.signal.aborted || token !== getToken()) throw new DOMException('Aborted', 'AbortError'); };
    try {
      current();
      const response = await fetchImpl(baseUrl.replace(/\/$/, '') + '/api/v1/esp32' + path, {
        method: payload ? 'POST' : 'GET', signal: controller.signal, cache: 'no-store',
        headers: { Authorization: `Bearer ${token || ''}`, ...(payload ? { 'Content-Type': 'application/json' } : {}) },
        ...(payload ? { body: JSON.stringify(payload) } : {}),
      });
      current(); const body = await response.json().catch(() => null); current();
      if (!response.ok || body?.code != null && body.code !== 0) {
        const code = typeof body?.code === 'string' ? body.code : body?.msg;
        throw Object.assign(new Error('DEVICE_INPUT_FAILED'), { status: response.status, code: /^[A-Za-z0-9_]{1,80}$/.test(code || '') ? code : 'unavailable' });
      }
      return body && 'data' in body ? body.data : body;
    } catch (error) {
      if (error.name === 'AbortError' && !signal?.aborted && token === getToken()) throw Object.assign(new Error('DEVICE_INPUT_TIMEOUT'), { code: 'timeout' });
      throw error;
    } finally { clearTimeout(timeout); signal?.removeEventListener('abort', abort); }
  }
  return {
    target: (agentId, mac, signal) => request('/text-input/target?' + new URLSearchParams({ agent_id: agentId, mac_address: mac }), { signal }),
    submit: (deviceId, payload, signal) => request('/devices/' + encodeURIComponent(deviceId) + '/text-input', { payload, signal }),
    command: (id, signal) => request('/commands/' + encodeURIComponent(id), { signal }),
  };
}
