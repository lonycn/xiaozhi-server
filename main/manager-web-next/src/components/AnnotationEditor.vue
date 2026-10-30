<template>
  <section class="annotation-workspace">
    <button type="button" :disabled="disabled || !conversationId || busy" @click="loadSnapshots(false)">{{ $t('annotations.saved') }}</button>
    <small v-if="!writable">{{ $t('annotations.readOnly') }}</small>
    <p v-if="error && !visible" role="alert">{{ $t(error) }}</p>
    <div v-if="listVisible" class="snapshot-list">
      <button type="button" :disabled="disabled || busy" @click="loadSnapshots(false)">{{ $t('annotations.refresh') }}</button>
      <p v-if="!snapshots.length && !busy">{{ $t('annotations.empty') }}</p>
      <button v-for="item in snapshots" :key="item.id" type="button" :disabled="disabled || busy" @click="openSnapshot(item.id)">{{ item.label }} · {{ $t('attachments.' + item.status) }}</button>
      <button v-if="listHasMore" type="button" :disabled="disabled || busy" @click="loadSnapshots(true)">{{ $t('annotations.more') }}</button>
    </div>
    <Teleport to="body">
      <div v-if="visible" class="annotation-backdrop" @keydown="dialogKeydown">
        <section ref="dialog" class="annotation-dialog" role="dialog" aria-modal="true" :aria-label="$t('annotations.editor')" tabindex="-1">
          <header><h2>{{ $t('annotations.editor') }}</h2><button type="button" @click="close">{{ $t('annotations.close') }}</button></header>
          <p v-if="error" class="annotation-error" role="alert">{{ $t(error) }}</p>
          <p v-if="busy" role="status">{{ $t('annotations.working') }}</p>
          <div v-if="source" class="annotation-source"><strong>{{ source.filename }}</strong><template v-if="source.kind === 'video'"><label>{{ $t('annotations.requestTime') }} <input v-model.number="requestedTimeMs" type="number" min="0" :max="Math.max(0, (source.metadata?.duration_ms || 1) - 1)" step="1" :disabled="disabled || busy || !writable" /> ms</label></template><button type="button" :disabled="disabled || busy || !writable" @click="capture">{{ $t(frame ? 'annotations.captureNew' : 'annotations.capture') }}</button></div>
          <p v-if="frame" class="frame-evidence">{{ $t('annotations.frame') }} {{ frame.id }}<template v-if="frame.requested_time_ms != null"> · {{ $t('annotations.requestTime') }} {{ frame.requested_time_ms }} ms · {{ $t('annotations.actualTime') }} {{ frame.actual_time_ms ?? '—' }} ms</template></p>
          <p v-if="frame && frame.status !== 'ready'">{{ $t('attachments.' + frame.status) }}</p>
          <div v-if="scene && background" class="annotation-tools">
            <button v-for="name in tools" :key="name" type="button" :aria-pressed="tool === name" :disabled="disabled || busy || !writable" @click="tool = name">{{ $t('annotations.' + name) }}</button>
            <label>{{ $t('annotations.color') }} <input v-model="color" type="color" :disabled="disabled || busy || !writable" /></label>
            <label>{{ $t('annotations.stroke') }} <select v-model.number="strokeWidth" :disabled="disabled || busy || !writable"><option :value="0.002">1</option><option :value="0.004">2</option><option :value="0.008">3</option></select></label>
            <button type="button" :disabled="disabled || busy || !writable || !history?.canUndo" @click="undo">{{ $t('annotations.undo') }}</button><button type="button" :disabled="disabled || busy || !writable || !history?.canRedo" @click="redo">{{ $t('annotations.redo') }}</button><button type="button" :disabled="disabled || busy || !writable || !scene.elements.length" @click="resetScene">{{ $t('annotations.reset') }}</button>
          </div>
          <div v-if="scene && background" ref="canvasWrap" class="annotation-canvas-wrap" :style="canvasLayout"><canvas ref="canvas" tabindex="0" :aria-label="$t('annotations.canvas')" :style="{ aspectRatio: `${scene.width} / ${scene.height}` }" @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="pointerUp" @pointercancel="cancelPointer" /></div>
          <div v-if="textPosition" class="annotation-text"><label>{{ $t('annotations.text') }}<textarea v-model="textDraft" maxlength="2000" :disabled="disabled || !writable || busy" @compositionstart="composing = true" @compositionend="composing = false" @keydown="textKeydown" /></label><label>{{ $t('annotations.fontSize') }}<select v-model.number="fontSize"><option :value="0.02">1</option><option :value="0.04">2</option><option :value="0.06">3</option></select></label><button type="button" :disabled="disabled || !writable || busy || !textDraft.trim()" @click="commitText">{{ $t('annotations.addText') }}</button><button type="button" @click="textPosition = null; textDraft = ''">{{ $t('attachments.remove') }}</button></div>
          <label v-if="scene" class="annotation-label">{{ $t('annotations.label') }} <input v-model="label" maxlength="120" :disabled="disabled || busy || !writable" /></label>
          <p v-if="scene" class="annotation-note">{{ $t('annotations.saveNote') }}</p><p v-if="requiresCopy" class="annotation-note">{{ $t('annotations.copyNote') }}</p>
          <p v-if="saved">{{ $t('annotations.snapshot') }} {{ saved.id }} · {{ $t('attachments.' + saved.status) }}<span v-if="dirty"> · {{ $t('annotations.unsaved') }}</span></p>
          <div v-if="previewUrl" class="annotation-preview"><strong>{{ $t('annotations.serverPreview') }}</strong><img :src="previewUrl" :alt="label" @error="previewFailed" /></div>
          <footer><button v-if="frame?.status === 'failed' || saved?.status === 'failed'" type="button" :disabled="disabled || busy || !writable" @click="retryCurrent">{{ $t('annotations.retry') }}</button><button type="button" :disabled="disabled || busy || !writable || !background || !scene || !label.trim() || !!textPosition && !!textDraft.trim()" @click="saveSnapshot">{{ $t(requiresCopy ? 'annotations.saveCopy' : 'annotations.save') }}</button><button type="button" :disabled="disabled || busy || !writable || requiresCopy || dirty || saved?.status !== 'ready' || !previewUrl" @click="addToReply">{{ $t('annotations.addReply') }}</button></footer>
        </section>
      </div>
    </Teleport>
  </section>
</template>
<script>
import { markRaw } from 'vue';
import annotationApi from '../apis/module/annotation';
import attachmentApi from '../apis/module/attachment';
import { privateAnnotationBlob, decodeAnnotationBlob } from '../apis/module/annotationClient.mjs';
import { abortableDelay } from '../utils/aiStream.mjs';
import { ANNOTATION_TOOLS, cloneScene, emptyScene, validateAnnotationScene, normalizedCanvasPoint, annotationShape, createAnnotationHistory, drawAnnotationScene, annotationErrorKey } from '../utils/annotationScene.mjs';
export default {
  name: 'AnnotationEditor',
  props: { conversationId: { type: String, default: '' }, scopeKey: { type: String, default: '' }, identity: { type: String, default: '' }, writable: Boolean, disabled: Boolean },
  emits: ['ready'],
  data: () => ({ draftKey: '', activeContext: '', visible: false, busy: false, error: '', source: null, frame: null, scene: null, history: null, saved: null, requiresCopy: false, label: '', requestedTimeMs: 0, framePending: null, annotationPending: null, savedSignature: '', localDrafts: {}, lastTimes: {}, tools: ANNOTATION_TOOLS, tool: 'arrow', color: '#E53935', strokeWidth: .004, fontSize: .04, textPosition: null, textDraft: '', composing: false, gesture: null, background: null, backgroundResource: null, previewResource: null, previewUrl: '', observer: null, controller: null, epoch: 0, disposed: false, snapshots: [], listVisible: false, listCursor: '0', listHasMore: false }),
  computed: { canWrite() { return this.writable && !this.disabled; }, canvasLayout() { const ratio = this.scene ? this.scene.width / this.scene.height : 1; return { width: `min(100%, ${55 * ratio}dvh)` }; }, context() { return JSON.stringify([this.identity,this.scopeKey,this.conversationId]); }, signature() { return this.scene ? JSON.stringify({ frame_id: this.scene.frame_id, label: this.label.trim(), scene: this.scene }) : ''; }, dirty() { return !this.saved || this.signature !== this.savedSignature; } },
  watch: {
    context: { flush: 'sync', handler() { const oldIdentity = this.activeContext ? JSON.parse(this.activeContext)[0] : this.identity; this.close(); if (oldIdentity !== this.identity) { this.localDrafts = {}; this.lastTimes = {}; } this.draftKey = ''; this.source = null; this.frame = null; this.scene = null; this.saved = null; this.snapshots = []; this.listVisible = false; this.error = ''; } },
    identity: { flush: 'sync', handler() { this.localDrafts = {}; this.lastTimes = {}; } },
    writable: { flush: 'sync', handler(value) { if (!value) { this.close(); this.error = 'annotations.denied'; } } },
    disabled: { flush: 'sync', handler(value) { if (value) this.close(); } },
  },
  beforeUnmount() { this.disposed = true; this.close(); },
  methods: {
    remember() {
      if (!this.draftKey || (!this.scene && !this.framePending)) return;
      const time = this.frame?.requested_time_ms ?? this.framePending?.body?.time_ms ?? this.requestedTimeMs ?? 0;
      const key = this.draftKey;
      this.localDrafts[key] = { frame: this.frame, scene: this.scene ? cloneScene(this.scene) : null, history: this.history, label: this.label, saved: this.saved, requiresCopy: this.requiresCopy, savedSignature: this.savedSignature, framePending: this.framePending, annotationPending: this.annotationPending };
      if (this.source) this.lastTimes[`${this.activeContext}:${this.source.id}`] = time;
    },
    cancelIO() { ++this.epoch; this.controller?.abort(); this.busy = false; this.cancelPointer(); },
    releaseImages() { this.backgroundResource?.release(); this.previewResource?.release(); this.backgroundResource = null; this.previewResource = null; this.background = null; this.previewUrl = ''; this.observer?.disconnect(); this.observer = null; },
    close() { this.remember(); this.cancelIO(); this.releaseImages(); this.visible = false; this.textPosition = null; },
    begin() { this.cancelIO(); const controller = markRaw(new AbortController()); this.controller = controller; this.busy = true; this.error = ''; return { controller, version: this.epoch, context: this.context }; },
    current(op) { return !this.disabled && !this.disposed && !op.controller.signal.aborted && op.version === this.epoch && op.context === this.context; },
    async finish(op, action) {
      try { await action(); }
      catch (error) { if (this.current(op) && error.name !== 'AbortError') this.error = annotationErrorKey(error); }
      finally { if (this.current(op)) { this.busy = false; this.remember(); await this.$nextTick(); if (this.current(op)) this.draw(); } }
    },
    async loadSnapshots(more = false) {
      if (this.disabled || !this.conversationId || this.busy) return;
      const op = this.begin(), after = more ? this.listCursor : '0'; this.listVisible = true;
      await this.finish(op, async () => {
        const page = await annotationApi.list(this.conversationId, after, op.controller.signal); if (!this.current(op)) return;
        if (page.has_more && (!/^\d+$/.test(String(page.next_cursor)) || BigInt(page.next_cursor) <= BigInt(after))) throw new Error('INVALID_CURSOR');
        this.snapshots = more ? [...this.snapshots, ...page.items.filter(item => !this.snapshots.some(old => old.id === item.id))] : page.items;
        this.listCursor = String(page.next_cursor); this.listHasMore = page.has_more;
      });
    },
    async openSource(source) {
      if (!this.canWrite || !this.conversationId) return;
      this.remember(); this.releaseImages(); this.activeContext = this.context; const op = this.begin(); this.visible = true;
      this.source = null; this.frame = null; this.scene = null; this.saved = null; this.requiresCopy = false; this.history = null; this.framePending = null; this.annotationPending = null; this.savedSignature = ''; this.label = this.$t('annotations.defaultLabel');
      await this.finish(op, async () => {
        const media = await attachmentApi.get(source.attachmentId, op.controller.signal); if (!this.current(op)) return;
        if (media.conversation_id !== this.conversationId || media.status !== 'ready' || !['image','video'].includes(media.kind)) throw new Error('INVALID_SOURCE');
        this.source = media; this.requestedTimeMs = this.lastTimes[`${this.context}:${media.id}`] || 0;
        this.draftKey = `${this.context}:${media.id}:${this.requestedTimeMs}`; const draft = this.localDrafts[this.draftKey];
        if (draft) {
          Object.assign(this,draft);
          if (this.frame && !['ready','failed','expired'].includes(this.frame.status)) await this.pollFrame(op);
          if (!this.current(op)) return;
          if (this.frame?.status === 'ready') { if (!this.scene) this.installScene(emptyScene(this.frame)); await this.loadBackground(op); }
          if (!this.current(op)) return;
          if (this.saved && !this.dirty) { await this.pollAnnotation(op); if (this.current(op) && this.saved.status === 'ready') await this.loadPreview(op); }
        }
      });
      if (this.current(op) && this.source?.kind === 'image' && !this.frame) await this.capture();
      this.$nextTick(() => this.$refs.dialog?.focus());
    },
    async capture() {
      if (!this.canWrite || !this.source || this.busy) return;
      if (this.source.kind === 'video' && (!Number.isInteger(this.requestedTimeMs) || this.requestedTimeMs < 0 || this.requestedTimeMs >= this.source.metadata?.duration_ms)) { this.error = 'annotations.invalidTime'; return; }
      this.remember(); this.releaseImages(); this.draftKey = `${this.context}:${this.source.id}:${this.source.kind === 'video' ? this.requestedTimeMs : 0}`;
      const body = { conversation_id: this.conversationId, source_attachment_id: this.source.id, ...(this.source.kind === 'video' ? { time_ms: this.requestedTimeMs } : {}) }, signature = JSON.stringify(body);
      if (this.framePending?.signature !== signature) this.framePending = { signature, body: { ...body, client_frame_id: crypto.randomUUID() } };
      const op = this.begin(); this.frame = null; this.scene = null; this.saved = null; this.requiresCopy = false; this.history = null; this.annotationPending = null; this.savedSignature = '';
      await this.finish(op, async () => {
        const frame = await annotationApi.createFrame(this.framePending.body, op.controller.signal); if (!this.current(op)) return;
        this.frame = frame; await this.pollFrame(op); if (!this.current(op) || this.frame.status !== 'ready') return;
        this.installScene(emptyScene(this.frame)); await this.loadBackground(op);
      });
    },
    async pollFrame(op) {
      while (this.current(op) && !['ready','failed','expired'].includes(this.frame.status)) { await abortableDelay(1500,op.controller.signal); if (!this.current(op)) return; const frame = await annotationApi.frame(this.frame.id,op.controller.signal); if (!this.current(op)) return; this.frame = frame; }
      if (this.current(op) && this.frame.status !== 'ready') this.error = 'annotations.failed';
    },
    installScene(scene) { validateAnnotationScene(scene,this.frame); this.scene = cloneScene(scene); this.history = markRaw(createAnnotationHistory(scene)); this.textPosition = null; },
    async loadBackground(op) {
      const blob = await privateAnnotationBlob(attachmentApi,this.frame.frame_attachment_id,op.controller.signal); if (!this.current(op)) return;
      const resource = await decodeAnnotationBlob(blob,op.controller.signal); if (!this.current(op)) { resource.release(); return; }
      if (resource.image.naturalWidth !== this.frame.width || resource.image.naturalHeight !== this.frame.height) { resource.release(); throw new Error('INVALID_BACKGROUND'); }
      this.backgroundResource?.release(); this.backgroundResource = markRaw(resource); this.background = markRaw(resource.image);
      await this.$nextTick(); if (!this.current(op)) return;
      this.observer?.disconnect(); this.observer = markRaw(new ResizeObserver(() => this.draw())); if (this.$refs.canvasWrap) this.observer.observe(this.$refs.canvasWrap); this.draw();
    },
    async loadPreview(op) {
      const blob = await privateAnnotationBlob(attachmentApi,this.saved.render_attachment_id,op.controller.signal); if (!this.current(op)) return;
      const resource = await decodeAnnotationBlob(blob,op.controller.signal); if (!this.current(op)) { resource.release(); return; }
      this.previewResource?.release(); this.previewResource = markRaw(resource); this.previewUrl = resource.image.src;
    },
    previewFailed() { this.previewResource?.release(); this.previewResource = null; this.previewUrl = ''; this.error = 'annotations.failed'; },
    async openSnapshot(id) {
      if (this.disabled || !this.conversationId || this.busy) return;
      this.remember(); this.releaseImages(); this.activeContext = this.context; this.draftKey = `${this.context}:snapshot:${id}`; const op = this.begin(); this.visible = true; this.source = null; this.frame = null; this.scene = null; this.saved = null; this.history = null; this.framePending = null; this.annotationPending = null;
      await this.finish(op, async () => {
        const item = await annotationApi.get(id,op.controller.signal); if (!this.current(op)) return;
        if (item.conversation_id !== this.conversationId) throw new Error('INVALID_SOURCE');
        const frame = await annotationApi.frame(item.frame_id,op.controller.signal); if (!this.current(op)) return;
        this.frame = frame; this.label = item.label; this.saved = item; this.requiresCopy = true; this.installScene(item.scene); this.savedSignature = this.signature;
        const draft = this.localDrafts[this.draftKey]; if (draft?.scene && draft.frame?.id === frame.id) Object.assign(this,draft);
        if (frame.status === 'ready') await this.loadBackground(op);
        if (this.current(op)) { await this.pollAnnotation(op); if (this.current(op) && this.saved.status === 'ready') await this.loadPreview(op); }
      });
      this.$nextTick(() => this.$refs.dialog?.focus());
    },
    async pollAnnotation(op) {
      while (this.current(op) && !['ready','failed','expired'].includes(this.saved.status)) { await abortableDelay(1500,op.controller.signal); if (!this.current(op)) return; const saved = await annotationApi.get(this.saved.id,op.controller.signal); if (!this.current(op)) return; this.saved = saved; }
      if (this.current(op) && this.saved.status !== 'ready') this.error = 'annotations.failed';
    },
    async saveSnapshot() {
      if (!this.canWrite || this.busy || !this.scene || !this.background || !this.label.trim()) return;
      try { validateAnnotationScene(this.scene,this.frame); } catch (error) { this.error = annotationErrorKey(error); return; }
      const signature = this.signature;
      if (this.annotationPending?.signature !== signature) this.annotationPending = { signature, body: { conversation_id: this.conversationId, client_annotation_id: crypto.randomUUID(), frame_id: this.frame.id, label: this.label.trim(), scene: cloneScene(this.scene) } };
      const op = this.begin(); this.previewFailed(); this.error = '';
      await this.finish(op, async () => {
        const item = !this.requiresCopy && !this.dirty && this.saved ? this.saved : await annotationApi.save(this.annotationPending.body,op.controller.signal); if (!this.current(op)) return;
        this.saved = item; this.requiresCopy = false; this.savedSignature = signature; await this.pollAnnotation(op);
        if (this.current(op) && this.saved.status === 'ready') await this.loadPreview(op);
      });
    },
    async retryCurrent() {
      if (!this.canWrite || this.busy) return;
      const op = this.begin();
      await this.finish(op, async () => {
        if (this.frame?.status === 'failed') { const item = await annotationApi.retryFrame(this.frame.id,op.controller.signal); if (!this.current(op)) return; this.frame = item; await this.pollFrame(op); if (this.current(op) && this.frame.status === 'ready') { this.installScene(emptyScene(this.frame)); await this.loadBackground(op); } }
        else if (this.saved?.status === 'failed') { const item = await annotationApi.retry(this.saved.id,op.controller.signal); if (!this.current(op)) return; this.saved = item; await this.pollAnnotation(op); if (this.current(op) && this.saved.status === 'ready') await this.loadPreview(op); }
      });
    },
    async addToReply() {
      if (!this.canWrite || this.busy || this.requiresCopy || this.dirty || this.saved?.status !== 'ready' || !this.previewUrl) return;
      const op = this.begin();
      await this.finish(op, async () => {
        const attachment = await attachmentApi.get(this.saved.render_attachment_id,op.controller.signal); if (!this.current(op) || !this.canWrite) return;
        if (attachment.status !== 'ready' || attachment.conversation_id !== this.conversationId || String(attachment.metadata?.annotation_id) !== this.saved.id || String(attachment.metadata?.frame_id) !== this.frame.id) throw new Error('INVALID_SOURCE');
        this.requiresCopy = true; this.annotationPending = null;
        this.$emit('ready',{ scopeKey: this.scopeKey, row: { kind: 'image', clientUploadId: `annotation-${this.saved.id}`, status: 'ready', progress: 100, error: '', attachment, annotation: this.saved } });
        this.close();
      });
    },
    draw() {
      const canvas = this.$refs.canvas; if (!canvas || !this.scene || !this.background) return;
      const rect = canvas.getBoundingClientRect(), dpr = globalThis.devicePixelRatio || 1;
      const width = Math.max(1,Math.round((rect.width || this.scene.width)*dpr)), height = Math.max(1,Math.round((rect.height || this.scene.height)*dpr));
      if (canvas.width !== width) canvas.width = width; if (canvas.height !== height) canvas.height = height;
      const ctx = canvas.getContext('2d'); if (!ctx) return; ctx.setTransform(width/this.scene.width,0,0,height/this.scene.height,0,0); drawAnnotationScene(ctx,this.scene,this.background,this.gesture?.element);
    },
    commit(scene) { try { validateAnnotationScene(scene,this.frame); this.scene = this.history.commit(scene); this.error = ''; this.draw(); this.remember(); return true; } catch (error) { this.error = annotationErrorKey(error); return false; } },
    undo() { if (!this.canWrite || this.busy || !this.history) return; this.scene = this.history.undo(); this.draw(); this.remember(); },
    redo() { if (!this.canWrite || this.busy || !this.history) return; this.scene = this.history.redo(); this.draw(); this.remember(); },
    resetScene() { if (!this.canWrite || this.busy || !this.history) return; this.scene = this.history.reset(); this.draw(); this.remember(); },
    pointerDown(event) {
      if (!this.canWrite || this.busy || !this.scene || !this.background || event.button > 0 || event.isPrimary === false) return;
      const point = normalizedCanvasPoint(event.clientX,event.clientY,event.currentTarget.getBoundingClientRect()); if (!point) return;
      if (this.tool === 'text') { this.textPosition = point; this.textDraft = ''; return; }
      event.preventDefault(); event.currentTarget.setPointerCapture?.(event.pointerId);
      this.gesture = { pointerId: event.pointerId, start: point, element: annotationShape(this.tool,point,point,this.scene,{ color:this.color,stroke_width:this.strokeWidth },crypto.randomUUID()) }; this.draw();
    },
    pointerMove(event) {
      if (!this.gesture || event.pointerId !== this.gesture.pointerId || !this.canWrite || this.busy) return;
      const point = normalizedCanvasPoint(event.clientX,event.clientY,event.currentTarget.getBoundingClientRect()); if (!point) return;
      if (this.gesture.element.type === 'freehand') { const points = this.gesture.element.points; if (points.length < 2000) points.push(point); }
      else this.gesture.element = annotationShape(this.gesture.element.type,this.gesture.start,point,this.scene,{ color:this.gesture.element.color,stroke_width:this.gesture.element.stroke_width },this.gesture.element.id);
      this.draw();
    },
    pointerUp(event) {
      if (!this.gesture || event.pointerId !== this.gesture.pointerId) return;
      this.pointerMove(event); const element = this.gesture.element; this.gesture = null; event.currentTarget.releasePointerCapture?.(event.pointerId);
      if (!this.canWrite || this.busy) { this.draw(); return; }
      if ((element.type === 'rect' && (!element.width || !element.height)) || (element.type === 'circle' && !element.radius)) { this.draw(); return; }
      this.commit({ ...this.scene, elements: [...this.scene.elements,element] });
    },
    cancelPointer() { this.gesture = null; this.draw(); },
    commitText() {
      if (!this.canWrite || this.busy || !this.textPosition || this.composing || !this.textDraft.trim()) return;
      if (this.commit({ ...this.scene,elements:[...this.scene.elements,{ id:crypto.randomUUID(),type:'text',color:this.color,x:this.textPosition.x,y:this.textPosition.y,text:this.textDraft,font_size:this.fontSize }] })) { this.textPosition = null; this.textDraft = ''; }
    },
    textKeydown(event) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && !this.composing && event.keyCode !== 229) { event.preventDefault(); this.commitText(); } },
    dialogKeydown(event) {
      if (event.isComposing || this.composing || event.keyCode === 229) return;
      if (event.key === 'Escape') { this.close(); event.preventDefault(); return; }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z' && !['INPUT','TEXTAREA','SELECT'].includes(event.target.tagName)) { event.preventDefault(); if (event.shiftKey) this.redo(); else this.undo(); }
      if (event.key === 'Tab') { const items = [...this.$refs.dialog.querySelectorAll('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),canvas')]; const first = items[0], last = items.at(-1); if (event.shiftKey && (event.target === first || event.target === this.$refs.dialog)) { event.preventDefault(); last?.focus(); } else if (!event.shiftKey && event.target === last) { event.preventDefault(); first?.focus(); } }
    },
  },
};
</script>
<style scoped>
.annotation-workspace { font-size: 12px; color: #64758a; margin: 8px 0; }.annotation-workspace > small { margin-left: 8px; }.snapshot-list { display: flex; flex-wrap: wrap; gap: 6px; max-height: 150px; overflow: auto; margin-top: 8px; }.annotation-workspace button,.annotation-dialog button { border: 1px solid #d5e0f0; border-radius: 5px; background: white; padding: 6px 10px; color: #386acf; cursor: pointer; font: inherit; }.annotation-workspace button:disabled,.annotation-dialog button:disabled { cursor: not-allowed; opacity: .5; }.annotation-backdrop { position: fixed; inset: 0; z-index: 3100; background: #17243c88; display: flex; align-items: center; justify-content: center; padding: 12px; }.annotation-dialog { background: white; color: #344968; width: min(1080px,100%); max-height: 95vh; overflow: auto; border-radius: 12px; padding: 18px; box-sizing: border-box; text-align: left; font-size: 13px; }.annotation-dialog header,.annotation-dialog footer,.annotation-tools,.annotation-source,.annotation-text { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin: 10px 0; }.annotation-dialog header { justify-content: space-between; }.annotation-dialog h2 { font-size: 18px; margin: 0; }.annotation-tools button[aria-pressed=true] { background: #e5edff; border-color: #739bed; }.annotation-dialog input,.annotation-dialog textarea,.annotation-dialog select { border: 1px solid #ccd8e8; border-radius: 4px; padding: 5px; font: inherit; box-sizing: border-box; max-width: 100%; }.annotation-source input { width: 130px; }.annotation-source strong { min-width: 0; overflow-wrap: anywhere; }.annotation-tools input[type=color] { width: 36px; height: 29px; padding: 2px; vertical-align: middle; }.annotation-canvas-wrap { background: #e8ecf2; max-width: 100%; max-height: 55dvh; margin: 12px auto; }.annotation-canvas-wrap canvas { display: block; width: 100%; max-height: none; touch-action: none; cursor: crosshair; }.annotation-text textarea { display: block; width: min(500px,70vw); min-height: 70px; }.annotation-error { color: #b34e37; background: #fff0e9; padding: 8px; border-radius: 5px; }.annotation-note,.frame-evidence { font-size: 12px; color: #73849a; overflow-wrap: anywhere; }.annotation-preview img { display: block; max-width: 100%; max-height: 280px; margin-top: 8px; object-fit: contain; }.annotation-label { display: block; margin: 10px 0; }.annotation-dialog footer { justify-content: flex-end; position: sticky; bottom: -18px; z-index: 1; background: white; padding: 10px 0; border-top: 1px solid #e1e8f1; }.annotation-dialog footer button { flex-shrink: 0; }button:focus-visible,canvas:focus-visible { outline: 2px solid #83a5f8; outline-offset: 2px; }@media(max-width:600px) { .annotation-backdrop { padding: 4px; }.annotation-dialog { padding: 12px; max-height: 98vh; }.annotation-tools { gap: 5px; }.annotation-dialog footer { bottom: -12px; }.annotation-dialog button { padding: 5px 7px; } }
</style>
