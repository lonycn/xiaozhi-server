<template>
  <div class="private-attachment">
    <strong>{{ attachment?.filename || $t('attachments.' + part.type) }}</strong>
    <small v-if="attachment">{{ $t('attachments.' + attachment.status) }}</small>
    <p v-if="error" role="alert">{{ $t(error) }}</p>
    <img v-if="url && part.type === 'image'" :src="url" :alt="attachment?.filename || $t('attachments.image')" referrerpolicy="no-referrer" @error="invalidate" />
    <video v-else-if="url && part.type === 'video'" :key="url" :src="url" controls preload="none" controlslist="nodownload" @error="invalidate" />
    <audio v-else-if="url && part.type === 'audio'" :key="url" :src="url" controls preload="none" @error="invalidate" />
    <div class="media-actions"><button v-if="part.type !== 'file'" type="button" :disabled="busy" @click="open(false)">{{ $t(expired ? 'attachments.reauthorize' : 'attachments.view') }}</button><button type="button" :disabled="busy" @click="open(true)">{{ $t('attachments.download') }}</button><button v-if="attachment?.metadata?.artifacts?.includes('transcript')" type="button" :disabled="busy" @click="open(true, 'transcript')">{{ $t('attachments.transcript') }}</button></div>
    <small v-for="job in transcriptionJobs" :key="job.id">{{ $t('attachments.transcription') }}: {{ $t('attachments.job_' + job.status) }}<template v-if="job.error_code"> · {{ $t('attachments.transcriptionFailed') }}</template></small>
    <small v-if="url">{{ $t('attachments.privateLink') }}</small>
  </div>
</template>
<script>
import attachmentApi from '../apis/module/attachment';
import { attachmentErrorKey } from '../utils/attachments.mjs';
export default {
  name: 'AttachmentPart',
  props: { part: { type: Object, required: true }, scopeKey: { type: String, required: true } },
  data: () => ({ attachment: null, url: '', error: '', expired: false, busy: false, controller: null, version: 0, expiryTimer: null }),
  computed: { transcriptionJobs() { return (this.attachment?.jobs || []).filter(job => job.job_type === 'audio_transcription'); }, context() { return JSON.stringify([this.scopeKey, this.part.attachment_id]); } },
  watch: { context: { flush: 'sync', handler() { this.reset(); } } },
  beforeUnmount() { this.reset(); },
  methods: {
    reset() { ++this.version; this.controller?.abort(); clearTimeout(this.expiryTimer); this.url = ''; this.attachment = null; this.error = ''; this.expired = false; this.busy = false; },
    invalidate() { clearTimeout(this.expiryTimer); this.url = ''; this.expired = true; this.error = 'attachments.expired'; },
    async open(download, explicitVariant) {
      if (this.busy) return;
      const version = ++this.version; clearTimeout(this.expiryTimer); this.url = ''; this.controller?.abort(); const controller = new AbortController(); this.controller = controller; this.busy = true; this.error = '';
      const current = () => version === this.version && !controller.signal.aborted;
      try {
        const attachment = await attachmentApi.get(this.part.attachment_id, controller.signal); if (!current()) return;
        this.attachment = attachment;
        if (attachment.status !== 'ready') { this.error = attachment.status === 'expired' ? 'attachments.expired' : 'attachments.notReady'; return; }
        const variant = explicitVariant || (download ? 'original' : this.part.type === 'image' ? 'display' : 'original');
        const ticket = await attachmentApi.access(attachment.id, variant, controller.signal); if (!current()) return;
        const expiry = typeof ticket.expires_at === 'number' || /^\d+$/.test(String(ticket.expires_at)) ? Number(ticket.expires_at) * (Number(ticket.expires_at) < 1e12 ? 1000 : 1) : Date.parse(ticket.expires_at);
        if (!Number.isFinite(expiry) || expiry <= Date.now()) { this.invalidate(); return; }
        if (download) {
          const link = document.createElement('a'); link.href = ticket.url; link.download = ticket.filename || attachment.filename; link.rel = 'noopener noreferrer'; link.referrerPolicy = 'no-referrer'; document.body.append(link); link.click(); link.remove();
        } else {
          clearTimeout(this.expiryTimer); this.url = ticket.url; this.expired = false;
          this.expiryTimer = setTimeout(() => { if (current()) this.invalidate(); }, Math.min(expiry - Date.now(), 120000));
        }
      } catch (error) { if (current() && error.name !== 'AbortError') this.error = attachmentErrorKey(error); }
      finally { if (current()) this.busy = false; }
    },
  },
};
</script>
<style scoped>
.private-attachment { display: flex; flex-direction: column; gap: 6px; min-width: 0; max-width: 100%; margin: 6px 0; overflow-wrap: anywhere; }.private-attachment strong { font-size: 13px; font-weight: 500; }.private-attachment small { font-size: 11px; color: #77889f; }.private-attachment img,.private-attachment video { display: block; max-width: min(360px,100%); max-height: 320px; border-radius: 6px; object-fit: contain; }.private-attachment audio { max-width: 100%; }.private-attachment p { color: #b34e37; margin: 0; font-size: 12px; }.media-actions { display: flex; flex-wrap: wrap; gap: 8px; }.media-actions button { border: 1px solid #d5e0f0; border-radius: 5px; padding: 5px 8px; background: white; color: #386acf; cursor: pointer; font: inherit; font-size: 12px; }.media-actions button:disabled { opacity: .5; cursor: not-allowed; }
</style>
