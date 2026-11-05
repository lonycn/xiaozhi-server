/** Read-only audit history for the current conversation. */
export function createToolStatusClient({ baseUrl, getToken, fetchImpl = globalThis.fetch, onUnauthorized = () => {}, prepare = async () => {} }) {
  return {
    async list(conversationId, before = '0', signal) {
      const controller = new AbortController();
      const abort = () => controller.abort();
      if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
      const timer = setTimeout(abort, 30000);
      try {
        await prepare();
        if (controller.signal.aborted) throw new DOMException('Aborted', 'AbortError');
        const token = getToken();
        const url = `${baseUrl.replace(/\/$/, '')}/api/v1/ai/conversations/${encodeURIComponent(conversationId)}/tools?before=${encodeURIComponent(String(before))}&limit=50`;
        const response = await fetchImpl(url, { signal: controller.signal, headers: { Authorization: `Bearer ${token || ''}` } });
        const body = await response.json().catch(() => null);
        if ((response.status === 401 || body?.code === 401) && token === getToken()) onUnauthorized();
        if (!response.ok || body?.code !== 0) {
          const status = response.status === 200 && Number.isInteger(body?.code) ? body.code : response.status;
          throw Object.assign(new Error(body?.msg || 'TOOL_STATUS_REQUEST_FAILED'), { status });
        }
        return body.data;
      } catch (error) {
        if (error.name === 'AbortError' && !signal?.aborted) throw Object.assign(new Error('TIMEOUT'), { status: 0 });
        throw error;
      } finally {
        clearTimeout(timer);
        signal?.removeEventListener('abort', abort);
      }
    },
  };
}
