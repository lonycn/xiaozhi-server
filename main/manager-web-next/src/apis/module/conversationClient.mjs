/** Async API client with explicit HTTP errors and injectable transport for tests. */
export function createConversationClient({ baseUrl, getToken, onUnauthorized, fetchImpl = globalThis.fetch, errorMessage }) {
  async function request(path = '', options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 30000);
    try {
      const response = await fetchImpl(`${baseUrl.replace(/\/$/, '')}/api/v1/ai/conversations${path}`, {
        ...options,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken() || ''}` },
        signal: controller.signal,
      });
      const body = await response.json().catch(() => null);
      if (response.status === 401 || body?.code === 401) {
        onUnauthorized();
        throw new Error('登录已过期，请重新登录');
      }
      if (!response.ok || body?.code !== 0) {
        if (errorMessage && /\/(handoff|resume-ai)$/.test(path)) throw Object.assign(new Error(errorMessage(body?.msg, response.status)), { code: body?.msg, status: response.status });
        const messages = {
          CLIENT_MESSAGE_ID_REUSED: '消息编号冲突，请刷新后再试',
          CONVERSATION_NOT_FOUND: '会话不存在或当前账号无权访问',
          MESSAGE_PART_NOT_SUPPORTED: '当前版本还不支持这种消息类型',
        };
        throw new Error(messages[body?.msg] || (response.status === 404 && (!path || path.startsWith('?'))
          ? '当前服务尚未启用统一对话，请检查开发环境配置'
          : body?.msg || '请求未成功，请稍后重试'));
      }
      return body.data;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('连接超时，内容已保留，可以重试');
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }
  return {
    detail: id => request(`/${encodeURIComponent(id)}`),
    events: (id, after = 0) => request(`/${encodeURIComponent(id)}/events?after=${after}&limit=100`),
    handoff: (id, payload) => request(`/${encodeURIComponent(id)}/handoff`, { method: 'POST', body: JSON.stringify(payload) }),
    resume: id => request(`/${encodeURIComponent(id)}/resume-ai`, { method: 'POST', body: '{}' }),
    list: (before = 0) => request(`?before=${before}&limit=50`),
    create: title => request('', { method: 'POST', body: JSON.stringify({ title }) }),
    messages: (id, after = 0) => request(`/${encodeURIComponent(id)}/messages?after=${after}&limit=100`),
    send: (id, payload) => request(`/${encodeURIComponent(id)}/messages`, { method: 'POST', body: JSON.stringify(payload) }),
  };
}
