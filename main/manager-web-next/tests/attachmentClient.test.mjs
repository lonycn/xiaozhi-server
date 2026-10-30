import { afterEach, describe, expect, it, vi } from 'vitest';
import { createAttachmentClient, attachmentApiUrl, xhrBinaryUpload } from '../src/apis/module/attachmentClient.mjs';
const response = (data, status = 200, code = 0) => ({ ok: status >= 200 && status < 300, status, json: async () => ({ code, data, msg: 'safe-code' }) });
function fixture() { const fetchImpl = vi.fn(), uploadImpl = vi.fn(), onUnauthorized = vi.fn(); const client = createAttachmentClient({ baseUrl: '/xiaozhi', getToken: () => 'long-lived-bearer', fetchImpl, uploadImpl, onUnauthorized }); return { client, fetchImpl, uploadImpl, onUnauthorized }; }
afterEach(() => vi.unstubAllGlobals());
describe('private attachment client', () => {
  it('preserves decimal IDs, JSON envelopes and auth headers', async () => {
    const f = fixture(); f.fetchImpl.mockResolvedValue(response({ id: '9223372036854775806' }));
    await f.client.init({ client_upload_id: 'stable' }); await f.client.complete('9223372036854775806');
    expect(f.fetchImpl.mock.calls[0][0]).toBe('/xiaozhi/api/v1/ai/uploads/init');
    expect(f.fetchImpl.mock.calls[0][1].headers.Authorization).toBe('Bearer long-lived-bearer');
    expect(f.fetchImpl.mock.calls[1][0]).toBe('/xiaozhi/api/v1/ai/uploads/9223372036854775806/complete'); expect(f.fetchImpl.mock.calls[1][1].body).toBe('{}');
  });
  it('uploads the unmodified binary over PUT using only the authorized API URL', async () => {
    const f = fixture(), binary = new Blob(['raw'], { type: 'image/png' }); f.uploadImpl.mockResolvedValue(response({ status: 'uploaded' }));
    await f.client.upload({ id: '9', upload: { method: 'PUT', url: '/xiaozhi/api/v1/ai/uploads/9/content' } }, binary, new AbortController().signal, vi.fn());
    const [url, payload, options] = f.uploadImpl.mock.calls[0]; expect(url).toMatch(/\/xiaozhi\/api\/v1\/ai\/uploads\/9\/content$/); expect(payload).toBe(binary); expect(options.token).toBe('long-lived-bearer');
    expect(url).not.toContain('bearer');
    await expect(f.client.upload({ id: '9', upload: { method: 'PUT', url: 'https://attacker.test/xiaozhi/api/v1/ai/uploads/9/content' } }, binary)).rejects.toThrow('UNSAFE_ATTACHMENT_URL'); expect(f.uploadImpl).toHaveBeenCalledOnce();
  });
  it('never includes long-lived auth in a URL and permits only one short-lived ticket', async () => {
    const f = fixture(); f.fetchImpl.mockResolvedValue(response({ url: '/xiaozhi/api/v1/ai/attachments/9/content?ticket=short-ticket', expires_at: '2026-10-30T11:00:00Z' }));
    const access = await f.client.access('9', 'display'); expect(access.url).toContain('?ticket=short-ticket'); expect(access.url).not.toContain('long-lived');
    expect(f.fetchImpl.mock.calls[0][0]).toBe('/xiaozhi/api/v1/ai/attachments/9/access?variant=display');
    for (const url of ['https://attacker.test/xiaozhi/api/v1/ai/attachments/9/content?ticket=x', '/xiaozhi/api/v1/ai/attachments/10/content?ticket=x', '/xiaozhi/api/v1/ai/attachments/9/content?token=long-lived-bearer', '/xiaozhi/api/v1/ai/attachments/9/content?ticket=a&ticket=b', '/xiaozhi/api/v1/ai/attachments/9/content?ticket=a&redirect=https://attacker.test']) {
      f.fetchImpl.mockResolvedValue(response({ url })); await expect(f.client.access('9', 'original')).rejects.toThrow('UNSAFE_ATTACHMENT_URL');
    }
  });
  it('rejects response errors and forwards cancellation without reporting success', async () => {
    const f = fixture(); f.fetchImpl.mockResolvedValue(response(null, 404, 404)); await expect(f.client.get('9')).rejects.toMatchObject({ status: 404 });
    f.fetchImpl.mockResolvedValue(response(null, 401, 401)); await expect(f.client.get('9')).rejects.toMatchObject({ status: 401 }); expect(f.onUnauthorized).toHaveBeenCalledOnce();
    const controller = new AbortController(); let requestSignal;
    f.fetchImpl.mockImplementation((_url, { signal }) => { requestSignal = signal; return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))); });
    const pending = f.client.get('9', controller.signal); controller.abort(); await expect(pending).rejects.toMatchObject({ name: 'AbortError' }); expect(requestSignal.aborted).toBe(true);
  });
  it('requires the exact configured API origin, context path and attachment endpoint', () => {
    expect(attachmentApiUrl('https://api.example.test/context', '/context/api/v1/ai/uploads/9/content', '/api/v1/ai/uploads/9/content')).toBe('https://api.example.test/context/api/v1/ai/uploads/9/content');
    expect(() => attachmentApiUrl('/xiaozhi', '/api/v1/ai/uploads/9/content', '/api/v1/ai/uploads/9/content')).toThrow();
  });
  it('performs a raw XHR PUT, reports byte progress, and aborts the active request', async () => {
    let xhr;
    vi.stubGlobal('XMLHttpRequest', class { constructor() { xhr = this; this.upload = {}; this.headers = {}; } open = vi.fn(); setRequestHeader(k,v) { this.headers[k] = v; } send = vi.fn(); abort() { this.onabort(); } });
    const progress = vi.fn(), controller = new AbortController(), binary = new Blob(['abc'], { type: 'text/plain' });
    const pending = xhrBinaryUpload('/api/content', binary, { token: 'secret', signal: controller.signal, onProgress: progress });
    expect(xhr.open).toHaveBeenCalledWith('PUT', '/api/content'); expect(xhr.send).toHaveBeenCalledWith(binary); expect(xhr.headers).toEqual({ 'Content-Type': 'text/plain', Authorization: 'Bearer secret' });
    xhr.upload.onprogress({ lengthComputable: true, loaded: 2, total: 3 }); expect(progress).toHaveBeenCalledWith(2, 3);
    controller.abort(); await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
  });
});
