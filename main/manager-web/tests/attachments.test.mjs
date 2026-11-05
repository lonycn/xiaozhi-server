import { webcrypto } from 'node:crypto';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ATTACHMENT_TYPES, attachmentValidation, fileSha256, mixedMessageParts, attachmentRowsReady, createAttachmentDraftController } from '../src/utils/attachments.mjs';
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const file = (type = 'image/png', name = '现场.png', size = 10) => ({ type, name, size });
const row = () => ({ file: file(), kind: 'image', clientUploadId: 'stable-upload-id', status: 'paused', progress: 0 });
function fixture(overrides = {}) {
  const item = row(), rows = [item];
  const client = { init: vi.fn().mockResolvedValue({ id: '9007199254740993', status: 'pending_upload' }), upload: vi.fn().mockResolvedValue({}), complete: vi.fn().mockResolvedValue({ id: '9007199254740993', status: 'processing' }), get: vi.fn().mockResolvedValue({ id: '9007199254740993', status: 'ready' }), retry: vi.fn().mockResolvedValue({ id: '9007199254740993', status: 'processing' }), ...overrides };
  const changed = vi.fn(), unavailable = vi.fn(), hash = vi.fn().mockResolvedValue('a'.repeat(64));
  const controller = createAttachmentDraftController({ client, rows: () => rows, changed, unavailable, hash, delay: async () => {} });
  controller.activate('conversation-one');
  return { item, rows, client, controller, changed, unavailable, hash };
}
afterEach(() => vi.unstubAllGlobals());
describe('attachment limits and message contract', () => {
  it.each(Object.entries(ATTACHMENT_TYPES))('accepts the exact upper bound for %s and rejects oversized files', (mime, spec) => {
    const value = file(mime, `evidence.${spec.extensions[0]}`, spec.max);
    expect(attachmentValidation(value)).toBeNull(); expect(attachmentValidation({ ...value, size: value.size + 1 })).toBe('size');
  });
  it('rejects active formats, extension mismatches, empty files, excess count and duplicate video/audio', () => {
    expect(attachmentValidation(file('image/svg+xml', 'x.svg'))).toBe('type');
    expect(attachmentValidation(file('text/plain', 'x.html'))).toBe('type');
    expect(attachmentValidation(file('image/png', 'x.png', 0))).toBe('size');
    expect(attachmentValidation(file(), Array(10).fill({ kind: 'image' }))).toBe('count');
    expect(attachmentValidation(file('video/mp4', 'x.mp4'), [{ kind: 'video' }])).toBe('count');
    expect(attachmentValidation(file('audio/mpeg', 'x.mp3'), [{ kind: 'audio' }])).toBe('count');
  });
  it('sends ordered IDs only, retaining text exactly and no upload or presentation fields', () => {
    const rows = [{ kind: 'video', status: 'ready', attachment: { id: '9223372036854775806', status: 'ready', url: 'secret' } }, { kind: 'file', status: 'ready', attachment: { id: '2', status: 'ready' } }];
    expect(mixedMessageParts('  note  ', rows)).toEqual([{ type: 'text', text: '  note  ' }, { type: 'video', attachment_id: '9223372036854775806' }, { type: 'file', attachment_id: '2' }]);
    expect(mixedMessageParts('', rows)).toHaveLength(2); expect(attachmentRowsReady(rows)).toBe(true);
    rows[0].attachment.status = 'processing'; expect(attachmentRowsReady(rows)).toBe(false);
  });
  it('computes an actual SHA-256 with WebCrypto and rejects insecure-context fallback', async () => {
    vi.stubGlobal('crypto', webcrypto);
    expect(await fileSha256({ arrayBuffer: async () => new TextEncoder().encode('abc').buffer })).toBe('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
    vi.stubGlobal('crypto', {}); await expect(fileSha256({})).rejects.toMatchObject({ code: 'WEBCRYPTO_REQUIRED' });
  });
});
describe('attachment draft lifecycle', () => {
  it('hashes, initializes, PUTs binary, completes then polls until ready', async () => {
    const f = fixture();
    f.client.upload.mockImplementation(async (_attachment, raw, signal, progress) => { expect(raw).toBe(f.item.file); expect(signal.aborted).toBe(false); progress(5, 10); expect(f.item.progress).toBe(50); });
    await f.controller.run(f.item);
    expect(f.client.init.mock.calls[0][0]).toEqual({ conversation_id: 'conversation-one', client_upload_id: 'stable-upload-id', filename: '现场.png', mime_type: 'image/png', size: 10, sha256: 'a'.repeat(64) });
    expect(f.item.status).toBe('ready'); expect(f.item.attachment.id).toBe('9007199254740993'); expect(f.client.complete).toHaveBeenCalledOnce(); expect(f.client.get).toHaveBeenCalledOnce();
  });
  it('retains the file and upload ID when complete fails and resumes without uploading again', async () => {
    const f = fixture(); f.client.complete.mockRejectedValueOnce(new Error('network'));
    await f.controller.run(f.item); expect(f.item.status).toBe('failed'); expect(f.item.file).toBeDefined();
    f.client.init.mockResolvedValue({ id: '9007199254740993', status: 'uploaded' });
    await f.controller.run(f.item);
    expect(f.item.status).toBe('ready'); expect(f.client.upload).toHaveBeenCalledOnce(); expect(f.hash).toHaveBeenCalledOnce();
    expect(f.client.init.mock.calls.map(call => call[0].client_upload_id)).toEqual(['stable-upload-id', 'stable-upload-id']);
  });
  it('uses the server retry endpoint only for a failed attachment', async () => {
    const f = fixture({ init: vi.fn().mockResolvedValue({ id: '7', status: 'failed' }) }); await f.controller.run(f.item);
    expect(f.client.retry).toHaveBeenCalledWith('7', expect.any(AbortSignal)); expect(f.client.upload).not.toHaveBeenCalled(); expect(f.item.status).toBe('ready');
  });
  it('does not mark rejected media ready and hides unavailable functionality honestly', async () => {
    const f = fixture({ get: vi.fn().mockResolvedValue({ id: '7', status: 'failed', error_code: 'TYPE_MISMATCH' }) }); await f.controller.run(f.item);
    expect(f.item.status).toBe('failed'); expect(f.item.error).toBe('attachments.invalidMedia');
    f.client.init.mockRejectedValue({ status: 404 }); await f.controller.run(f.item);
    expect(f.item.status).toBe('failed'); expect(f.item.error).toBe('attachments.unavailable'); expect(f.unavailable).toHaveBeenCalledOnce();
  });
  it('fences a late digest after context switch', async () => {
    const f = fixture(), digest = deferred(); f.hash.mockReturnValue(digest.promise);
    const pending = f.controller.run(f.item); f.controller.activate('conversation-two'); digest.resolve('b'.repeat(64)); await pending;
    expect(f.item.status).toBe('paused'); expect(f.item.sha256).toBeUndefined(); expect(f.client.init).not.toHaveBeenCalled();
  });
  it.each(['init', 'upload', 'get'])('aborts and fences a late %s response without touching the new conversation', async stage => {
    const f = fixture(), response = deferred(); f.client[stage].mockReturnValueOnce(response.promise);
    const pending = f.controller.run(f.item); await vi.waitFor(() => expect(f.client[stage]).toHaveBeenCalled());
    const signal = f.client[stage].mock.calls[0][stage === 'init' ? 1 : stage === 'upload' ? 2 : 1];
    f.controller.activate('conversation-two'); const old = JSON.stringify(f.item); response.resolve({ id: 'late', status: 'ready' }); await pending;
    expect(signal.aborted).toBe(true); expect(JSON.stringify(f.item)).toBe(old); expect(f.item.status).toBe('paused');
  });
  it('cancels removal and teardown while ignoring late progress', async () => {
    const f = fixture(), response = deferred(); let progress;
    f.client.upload.mockImplementation((_attachment, _file, _signal, cb) => { progress = cb; return response.promise; });
    const pending = f.controller.run(f.item); await vi.waitFor(() => expect(progress).toBeDefined());
    f.controller.remove(f.item); f.rows.splice(0); progress(100, 100); response.resolve({}); await pending;
    expect(f.client.complete).not.toHaveBeenCalled(); f.controller.cancel();
  });
});
