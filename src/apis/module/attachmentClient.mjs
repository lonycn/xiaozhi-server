/** Private attachment transport. Credentials only travel in API request headers. */
export function attachmentApiUrl(baseUrl, value, expectedPath, origin = globalThis.location?.origin || 'http://localhost') {
  const base = new URL(`${baseUrl.replace(/\/$/, '')}/`, origin);
  const url = new URL(value, base);
  const prefix = base.pathname.replace(/\/$/, '');
  if (url.origin !== base.origin || url.pathname !== `${prefix}${expectedPath}` || url.username || url.password || url.hash) {
    throw Object.assign(new Error('UNSAFE_ATTACHMENT_URL'), { code: 'UNSAFE_ATTACHMENT_URL' });
  }
  return url.href;
}

export function xhrBinaryUpload(url, file, { token, signal, onProgress }) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    const finish = () => signal?.removeEventListener('abort', abort);
    xhr.open('PUT', url); xhr.timeout = 120000;
    xhr.setRequestHeader('Content-Type', file.type);
    xhr.setRequestHeader('Authorization', `Bearer ${token || ''}`);
    xhr.upload.onprogress = event => { if (event.lengthComputable && !signal?.aborted) onProgress?.(event.loaded, event.total); };
    xhr.onload = () => { finish(); let body; try { body = JSON.parse(xhr.responseText); } catch { body = null; } resolve({ ok: xhr.status >= 200 && xhr.status < 300, status: xhr.status, json: async () => body }); };
    xhr.onerror = () => { finish(); reject(Object.assign(new Error('NETWORK'), { code: 'NETWORK' })); };
    xhr.ontimeout = () => { finish(); reject(Object.assign(new Error('TIMEOUT'), { code: 'TIMEOUT' })); };
    xhr.onabort = () => { finish(); reject(new DOMException('Aborted', 'AbortError')); };
    if (signal?.aborted) { finish(); reject(new DOMException('Aborted', 'AbortError')); return; }
    signal?.addEventListener('abort', abort, { once: true });
    xhr.send(file);
  });
}

export function createAttachmentClient({ baseUrl, getToken, fetchImpl = globalThis.fetch, uploadImpl = xhrBinaryUpload, prepareUpload, onUnauthorized = () => {} }) {
  const root = `${baseUrl.replace(/\/$/, '')}/api/v1/ai`;
  async function unpack(response) {
    const body = await response.json().catch(() => null);
    if (response.status === 401 || body?.code === 401) onUnauthorized();
    if (!response.ok || body?.code !== 0) throw Object.assign(new Error('ATTACHMENT_REQUEST_FAILED'), { status: response.status, code: body?.msg || 'ATTACHMENT_REQUEST_FAILED' });
    return body.data;
  }
  async function request(path, method = 'GET', payload, signal) {
    const controller = new AbortController(); const abort = () => controller.abort();
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
    const timeout = setTimeout(abort, 30000);
    try {
      return await unpack(await fetchImpl(`${root}${path}`, { method, signal: controller.signal,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken() || ''}` },
        ...(payload === undefined ? {} : { body: JSON.stringify(payload) }) }));
    } catch (error) {
      if (error.name === 'AbortError' && !signal?.aborted) throw Object.assign(new Error('TIMEOUT'), { code: 'TIMEOUT' });
      throw error;
    } finally { clearTimeout(timeout); signal?.removeEventListener('abort', abort); }
  }
  const idPath = id => { if (!/^[0-9]+$/.test(String(id))) throw new Error('INVALID_ATTACHMENT_ID'); return String(id); };
  return {
    init: (payload, signal) => request('/uploads/init', 'POST', payload, signal),
    async upload(attachment, file, signal, onProgress) {
      const path = `/api/v1/ai/uploads/${idPath(attachment.id)}/content`;
      const url = attachmentApiUrl(baseUrl, attachment.upload?.url, path);
      if (new URL(url).search || attachment.upload?.method !== 'PUT') throw new Error('UNSAFE_ATTACHMENT_URL');
      await prepareUpload?.();
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      return unpack(await uploadImpl(url, file, { token: getToken(), signal, onProgress }));
    },
    complete: (id, signal) => request(`/uploads/${idPath(id)}/complete`, 'POST', {}, signal),
    get: (id, signal) => request(`/attachments/${idPath(id)}`, 'GET', undefined, signal),
    retry: (id, signal) => request(`/attachments/${idPath(id)}/retry`, 'POST', {}, signal),
    async access(id, variant, signal) {
      if (!/^(display|thumbnail|original|poster|text|audio|transcript|frame_[0-5])$/.test(variant)) throw new Error('INVALID_VARIANT');
      const result = await request(`/attachments/${idPath(id)}/access?variant=${variant}`, 'GET', undefined, signal);
      const url = attachmentApiUrl(baseUrl, result.url, `/api/v1/ai/attachments/${idPath(id)}/content`);
      const parsed = new URL(url);
      if (Array.from(parsed.searchParams.keys()).some(key => key !== 'ticket') || parsed.searchParams.getAll('ticket').length !== 1 || !parsed.searchParams.get('ticket')) throw new Error('UNSAFE_ATTACHMENT_URL');
      return { ...result, url };
    },
  };
}
