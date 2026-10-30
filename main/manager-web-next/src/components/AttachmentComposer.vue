<template>
  <section class="attachment-composer" :aria-label="$t('attachments.draft')">
    <div class="attachment-toolbar"><label class="attachment-picker" :class="{ disabled: disabled || !conversationId || unavailable }"><input ref="picker" type="file" multiple :accept="accept" :disabled="disabled || !conversationId || unavailable" @change="choose" /><span>{{ $t('attachments.add') }}</span></label><small>{{ $t('attachments.limits') }}</small></div>
    <p v-if="unavailable" role="status">{{ $t('attachments.unavailable') }}</p>
    <p v-if="validationError" role="alert">{{ $t(validationError) }}</p>
    <ol v-if="modelValue.length" class="attachment-list">
      <li v-for="(row, index) in modelValue" :key="row.clientUploadId">
        <div class="attachment-info"><strong>{{ (row.file?.name || row.attachment?.filename) }}</strong><small>{{ formatSize((row.file?.size ?? row.attachment?.size ?? 0)) }} · {{ $t('attachments.' + row.status) }}<template v-if="row.status === 'uploading'"> {{ row.progress }}%</template></small><progress v-if="row.status === 'uploading'" :value="row.progress" max="100" :aria-label="$t('attachments.uploading')" /><span v-if="row.error" class="attachment-error" role="alert">{{ $t(row.error) }}</span></div>
        <div class="attachment-actions"><button v-if="['failed', 'paused'].includes(row.status) && !unavailable" type="button" :disabled="disabled" @click="retry(row)">{{ $t('attachments.retry') }}</button><button type="button" :disabled="disabled || index === 0" :aria-label="$t('attachments.moveUp')" @click="move(index, -1)">↑</button><button type="button" :disabled="disabled || index === modelValue.length - 1" :aria-label="$t('attachments.moveDown')" @click="move(index, 1)">↓</button><button type="button" :disabled="disabled" :aria-label="$t('attachments.remove') + ': ' + (row.file?.name || row.attachment?.filename)" @click="remove(row)">{{ $t('attachments.remove') }}</button></div>
      </li>
    </ol>
    <p v-if="modelValue.length && !ready" class="attachment-wait" role="status">{{ $t('attachments.wait') }}</p>
  </section>
</template>
<script>
import { markRaw } from 'vue';
import attachmentApi from '../apis/module/attachment';
import { ATTACHMENT_ACCEPT, ATTACHMENT_TYPES, attachmentValidation, attachmentRowsReady, createAttachmentDraftController } from '../utils/attachments.mjs';
export default {
  name: 'AttachmentComposer',
  props: { modelValue: { type: Array, default: () => [] }, conversationId: { type: String, default: '' }, identity: { type: String, default: '' }, disabled: Boolean },
  emits: ['update:modelValue'],
  data: () => ({ accept: ATTACHMENT_ACCEPT, validationError: '', unavailable: false, controller: null }),
  computed: { ready() { return attachmentRowsReady(this.modelValue); }, context() { return JSON.stringify([this.conversationId, this.identity]); } },
  watch: { context: { flush: 'sync', handler() { this.controller?.activate(this.conversationId); this.validationError = ''; } }, identity() { this.unavailable = false; } },
  created() {
    this.controller = markRaw(createAttachmentDraftController({ client: attachmentApi, rows: () => this.modelValue, changed: () => this.$emit('update:modelValue', [...this.modelValue]), unavailable: () => { this.unavailable = true; } }));
    this.controller.activate(this.conversationId);
  },
  beforeUnmount() { this.controller.cancel(); },
  methods: {
    formatSize(size) { return size < 1024 * 1024 ? `${Math.ceil(size / 1024)} KB` : `${(size / (1024 * 1024)).toFixed(1)} MB`; },
    async choose(event) {
      const files = Array.from(event.target.files || []); event.target.value = '';
      if (this.disabled || !this.conversationId || this.unavailable) return;
      this.validationError = ''; const next = [...this.modelValue], added = [];
      for (const file of files) {
        const error = attachmentValidation(file, next);
        if (error) { this.validationError = `attachments.${error}`; continue; }
        const row = { file: markRaw(file), clientUploadId: crypto.randomUUID(), kind: ATTACHMENT_TYPES[file.type].kind, status: 'paused', progress: 0, error: '', sha256: '', attachment: null };
        next.push(row); added.push(row);
      }
      this.$emit('update:modelValue', next); await this.$nextTick();
      // Use reactive row identities from the parent model, never raw pre-emit objects.
      for (const row of added) { const current = this.modelValue.find(item => item.clientUploadId === row.clientUploadId); if (current) this.controller.run(current); }
    },
    retry(row) { if (!this.disabled) this.controller.run(row); },
    remove(row) { if (this.disabled) return; this.controller.remove(row); this.$emit('update:modelValue', this.modelValue.filter(item => item.clientUploadId !== row.clientUploadId)); },
    move(index, delta) { if (this.disabled) return; const next = [...this.modelValue]; [next[index], next[index + delta]] = [next[index + delta], next[index]]; this.$emit('update:modelValue', next); },
  },
};
</script>
<style scoped>
.attachment-composer { margin: 8px 0; font-size: 12px; text-align: left; color: #65758b; }
.attachment-toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.attachment-picker { display: inline-flex; position: relative; overflow: hidden; border: 1px solid #dce5f6; border-radius: 6px; padding: 6px 10px; color: #386acf; background: #f7faff; cursor: pointer; }
.attachment-picker:focus-within { outline: 2px solid #83a5f8; outline-offset: 2px; }.attachment-picker.disabled { opacity: .5; cursor: not-allowed; }
.attachment-picker input { position: absolute; inset: 0; opacity: 0; width: 100%; cursor: inherit; }
.attachment-toolbar small { flex: 1; min-width: 180px; line-height: 1.6; }.attachment-list { padding: 0; list-style: none; max-height: 180px; overflow: auto; }
.attachment-list li { display: flex; gap: 8px; align-items: center; justify-content: space-between; border: 1px solid #e3e9f2; padding: 8px; border-radius: 6px; margin-top: 5px; }
.attachment-info { min-width: 0; flex: 1; }.attachment-info strong { display: block; overflow-wrap: anywhere; color: #344968; font-weight: 500; }.attachment-info small,.attachment-error { display: block; margin-top: 3px; }
.attachment-error { color: #b34e37; }.attachment-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 4px; max-width: 45%; }.attachment-actions button { border: 1px solid #dbe4f0; border-radius: 4px; background: white; padding: 4px 6px; font: inherit; color: #466690; cursor: pointer; }.attachment-actions button:disabled { opacity: .45; cursor: not-allowed; }progress { width: 100%; height: 5px; }.attachment-wait { margin: 6px 0 0; }
</style>
