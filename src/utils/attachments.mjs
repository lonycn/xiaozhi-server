import { abortableDelay } from './aiStream.mjs';
const MiB = 1024 * 1024;
export const ATTACHMENT_TYPES = Object.freeze({
  'image/jpeg': { kind: 'image', max: 20 * MiB, extensions: ['jpg', 'jpeg'] },
  'image/png': { kind: 'image', max: 20 * MiB, extensions: ['png'] },
  'image/webp': { kind: 'image', max: 20 * MiB, extensions: ['webp'] },
  'application/pdf': { kind: 'file', max: 20 * MiB, extensions: ['pdf'] },
  'text/plain': { kind: 'file', max: 20 * MiB, extensions: ['txt'] },
  'video/mp4': { kind: 'video', max: 100 * MiB, extensions: ['mp4'] },
  'video/webm': { kind: 'video', max: 100 * MiB, extensions: ['webm'] },
  'audio/wav': { kind: 'audio', max: 50 * MiB, extensions: ['wav'] },
  'audio/mpeg': { kind: 'audio', max: 50 * MiB, extensions: ['mp3'] },
  'audio/mp4': { kind: 'audio', max: 50 * MiB, extensions: ['m4a'] },
  'audio/ogg': { kind: 'audio', max: 50 * MiB, extensions: ['ogg', 'oga'] },
});
export const ATTACHMENT_ACCEPT = Object.keys(ATTACHMENT_TYPES).join(',');
export function attachmentValidation(file, rows = []) {
  const type = ATTACHMENT_TYPES[file.type];
  if (!type || !type.extensions.includes(file.name.split('.').pop().toLowerCase())) return 'type';
  if (!Number.isSafeInteger(file.size) || file.size <= 0 || file.size > type.max) return 'size';
  if (rows.length >= 10 || (['video', 'audio'].includes(type.kind) && rows.some(row => row.kind === type.kind))) return 'count';
  return null;
}
export function attachmentErrorKey(error) {
  if (['ATTACHMENT_NOT_FOUND', 'CONVERSATION_NOT_FOUND'].includes(error.code)) return 'attachments.denied';
  if (error.status === 404 || error.code === 'ATTACHMENTS_DISABLED') return 'attachments.unavailable';
  if (error.code === 'WEBCRYPTO_REQUIRED') return 'attachments.secureContext';
  if (error.status === 401 || error.status === 403) return 'attachments.denied';
  if (error.code === 'TIMEOUT') return 'attachments.timeout';
  if (error.code === 'expired' || error.code === 'ATTACHMENT_EXPIRED') return 'attachments.expired';
  if (['INVALID_MEDIA', 'TYPE_MISMATCH', 'PIXEL_LIMIT', 'DURATION_LIMIT', 'PAGE_LIMIT'].includes(error.code)) return 'attachments.invalidMedia';
  return 'attachments.failed';
}
export async function fileSha256(file) {
  if (!globalThis.crypto?.subtle) throw Object.assign(new Error('WEBCRYPTO_REQUIRED'), { code: 'WEBCRYPTO_REQUIRED' });
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
}
export function mixedMessageParts(text, rows = []) {
  return [...(text.trim() ? [{ type: 'text', text }] : []), ...rows.map(row => ({ type: row.kind, attachment_id: row.attachment.id }))];
}
export function attachmentRowsReady(rows = []) { return rows.every(row => row.status === 'ready' && row.attachment?.status === 'ready'); }
export function attachmentIdentity(state) {
  const user = state.userInfo || {};
  return `${user.id || user.user_id || user.userId || user.username || ''}:${!!state.token}`;
}

/** One controller per composer; epoch + abort fences hash, HTTP, polling and progress. */
export function createAttachmentDraftController({ client, rows, changed = () => {}, unavailable = () => {}, hash = fileSha256, delay = abortableDelay }) {
  let epoch = 0, conversationId = ''; const active = new Map();
  function cancel() {
    ++epoch;
    for (const [row, controller] of active) { controller.abort(); if (!['ready', 'failed'].includes(row.status)) row.status = 'paused'; }
    active.clear();
  }
  return {
    activate(id) { cancel(); conversationId = id || ''; },
    cancel,
    remove(row) { for (const [item, controller] of active) { if (item.clientUploadId === row.clientUploadId) { controller.abort(); active.delete(item); } } },
    async run(row) {
      if (!conversationId || [...active.keys()].some(item => item.clientUploadId === row.clientUploadId)) return;
      const version = epoch, id = conversationId, controller = new AbortController(), { signal } = controller;
      active.set(row, controller);
      const current = () => version === epoch && !signal.aborted && rows().some(item => item.clientUploadId === row.clientUploadId);
      const update = attachment => { if (current()) { row.attachment = attachment; changed(); } };
      try {
        row.error = ''; row.status = 'hashing'; changed();
        if (!row.sha256) { const digest = await hash(row.file); if (!current()) return; row.sha256 = digest; }
        if (!current()) return;
        let attachment = await client.init({ conversation_id: id, client_upload_id: row.clientUploadId, filename: row.file.name, mime_type: row.file.type, size: row.file.size, sha256: row.sha256 }, signal);
        if (!current()) return; update(attachment);
        if (attachment.status === 'pending_upload') {
          row.status = 'uploading'; row.progress = 0; changed();
          await client.upload(attachment, row.file, signal, (loaded, total) => { if (current()) { row.progress = total ? Math.min(100, Math.floor(loaded / total * 100)) : 0; changed(); } });
          if (!current()) return;
          attachment = await client.complete(attachment.id, signal); if (!current()) return; update(attachment);
        } else if (attachment.status === 'uploaded') { attachment = await client.complete(attachment.id, signal); if (!current()) return; update(attachment); }
        else if (attachment.status === 'failed') { attachment = await client.retry(attachment.id, signal); if (!current()) return; update(attachment); }
        while (current() && !['ready', 'failed', 'expired'].includes(attachment.status)) {
          row.status = 'processing'; row.progress = 100; changed();
          await delay(1500, signal); if (!current()) return;
          attachment = await client.get(attachment.id, signal); if (!current()) return; update(attachment);
        }
        if (!current()) return;
        if (attachment.status !== 'ready') throw Object.assign(new Error('MEDIA_NOT_READY'), { code: attachment.error_code || attachment.status });
        row.status = 'ready'; row.progress = 100; changed();
      } catch (error) {
        if (!current()) return;
        row.status = 'failed'; row.error = attachmentErrorKey(error);
        if (row.error === 'attachments.unavailable') unavailable();
        changed();
      } finally { if (active.get(row) === controller) active.delete(row); }
    },
  };
}
