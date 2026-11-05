export function createAnnotationClient({ baseUrl, getToken, fetchImpl = globalThis.fetch, onUnauthorized = () => {} }) {
  const root = `${baseUrl.replace(/\/$/, '')}/api/v1/support`;
  const id = value => { if (!/^[1-9][0-9]*$/.test(String(value))) throw new Error('INVALID_ANNOTATION_ID'); return String(value); };
  async function request(path, method = 'GET', payload, signal) {
    const controller = new AbortController(), abort = () => controller.abort();
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
    const timer = setTimeout(abort, 30000);
    try {
      const response = await fetchImpl(`${root}${path}`, { method, signal: controller.signal,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${getToken() || ''}` },
        ...(payload === undefined ? {} : { body: JSON.stringify(payload) }) });
      const body = await response.json().catch(() => null);
      if (response.status === 401 || body?.code === 401) onUnauthorized();
      if (!response.ok || body?.code !== 0) throw Object.assign(new Error('ANNOTATION_REQUEST_FAILED'), { status: response.status, code: body?.msg || 'ANNOTATION_REQUEST_FAILED' });
      return body.data;
    } catch (error) {
      if (error.name === 'AbortError' && !signal?.aborted) throw Object.assign(new Error('TIMEOUT'), { code: 'TIMEOUT' });
      throw error;
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
  }
  return {
    createFrame: (body, signal) => request('/frames', 'POST', body, signal),
    frame: (value, signal) => request(`/frames/${id(value)}`, 'GET', undefined, signal),
    retryFrame: (value, signal) => request(`/frames/${id(value)}/retry`, 'POST', {}, signal),
    save: (body, signal) => request('/annotations', 'POST', body, signal),
    get: (value, signal) => request(`/annotations/${id(value)}`, 'GET', undefined, signal),
    retry: (value, signal) => request(`/annotations/${id(value)}/retry`, 'POST', {}, signal),
    list: (conversationId, after = '0', signal) => request(`/annotations?conversation_id=${encodeURIComponent(conversationId)}&after=${encodeURIComponent(after)}&limit=50`, 'GET', undefined, signal),
  };
}

/** The content route authenticates its own short ticket. Never use authenticatedFetch. */
export async function privateAnnotationBlob(attachmentClient, attachmentId, signal, fetchImpl = globalThis.fetch) {
  const controller = new AbortController(), abort = () => controller.abort();
  if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
  const timer = setTimeout(abort, 30000);
  try { return await downloadAnnotationBlob(attachmentClient,attachmentId,controller.signal,fetchImpl); }
  finally { clearTimeout(timer); signal?.removeEventListener('abort',abort); }
}
async function downloadAnnotationBlob(attachmentClient, attachmentId, signal, fetchImpl) {
  for (let attempt = 0; attempt < 2; attempt++) {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const ticket = await attachmentClient.access(attachmentId, 'display', signal);
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const response = await fetchImpl(ticket.url, { signal, credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', headers: { Accept: 'image/jpeg,image/png,image/webp' } });
    if ([401, 403].includes(response.status) && attempt === 0) { await response.body?.cancel().catch(() => {}); continue; }
    if (!response.ok) throw Object.assign(new Error('PRIVATE_IMAGE_FAILED'), { status: response.status });
    const mime = (response.headers.get('Content-Type') || '').split(';')[0];
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(mime)) { await response.body?.cancel().catch(() => {}); throw new Error('INVALID_BACKGROUND'); }
    const max = 20 * 1024 * 1024;
    if (Number(response.headers.get('Content-Length')) > max) { await response.body?.cancel().catch(() => {}); throw new Error('INVALID_BACKGROUND'); }
    const reader = response.body?.getReader(); let blob;
    if (reader) {
      let size = 0; const chunks = [];
      try { while (true) { const { done, value } = await reader.read(); if (done) break; size += value.byteLength; if (size > max) throw new Error('INVALID_BACKGROUND'); chunks.push(value); } blob = new Blob(chunks, { type: mime }); }
      catch (error) { await reader.cancel().catch(() => {}); throw error; }
      finally { reader.releaseLock(); }
    } else blob = await response.blob();
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    if (!blob.size || blob.size > max) throw new Error('INVALID_BACKGROUND');
    return blob;
  }
}

export function decodeAnnotationBlob(blob, signal) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(blob), image = new Image(); let released = false;
    const release = () => { if (released) return; released = true; image.src = ''; URL.revokeObjectURL(url); };
    const abort = () => { cleanup(); release(); reject(new DOMException('Aborted', 'AbortError')); };
    const cleanup = () => { signal?.removeEventListener('abort', abort); image.onload = null; image.onerror = null; };
    image.onload = () => { cleanup(); if (signal?.aborted) { release(); reject(new DOMException('Aborted', 'AbortError')); } else resolve({ image, release }); };
    image.onerror = () => { cleanup(); release(); reject(new Error('INVALID_BACKGROUND')); };
    if (signal?.aborted) { cleanup(); abort(); return; }
    signal?.addEventListener('abort', abort, { once: true });
    image.src = url;
  });
}
