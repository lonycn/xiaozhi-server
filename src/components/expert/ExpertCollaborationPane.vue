<template>
  <div ref="pane" class="collaboration-pane" :class="{ 'has-media': mediaVisible, 'has-video': mediaState.hasVideo, 'has-call': mediaState.active, 'has-annotation': annotationVisible, 'is-preparing': mediaState.preparing, 'is-stacked': stacked, 'is-resizing': !!drag }" :style="{ '--video-width': `${videoWidth}px` }">
    <section class="collaboration-media" :aria-label="$t('expertWorkspace.liveVideo')" data-workspace-region="media"><slot name="media" /></section>
    <div v-show="mediaVisible && !stacked" class="collaboration-divider" role="separator" tabindex="0" aria-orientation="vertical" :aria-label="$t('expertWorkspace.resizePanels')" :aria-valuemin="300" :aria-valuemax="maxVideoWidth" :aria-valuenow="videoWidth" :aria-valuetext="`${videoWidth}px`" @keydown="resizeKeydown" @pointerdown="startResize" @pointermove="moveResize" @pointerup="endResize" @pointercancel="endResize" @lostpointercapture="drag = null"><span /></div>
    <div class="collaboration-communication" data-workspace-region="communication">
      <div class="collaboration-annotation"><slot name="annotation" /></div>
      <div class="expert-content collaboration-chat"><slot /></div>
      <slot name="reply" />
    </div>
  </div>
</template>
<script>
export default {
  name: 'ExpertCollaborationPane',
  props: { annotationVisible: Boolean, mediaState: { type: Object, default: () => ({ active: false, hasVideo: false }) } },
  data: () => ({ width: 0, height: 0, preferredVideoWidth: null, drag: null, observer: null }),
  computed: {
    mediaVisible() { return Boolean(this.mediaState.visible ?? this.mediaState.active); },
    stacked() { return this.width > 0 && this.width < 712; },
    maxVideoWidth() { return Math.max(300, Math.min(460, this.width ? this.width - 400 - 12 : 460)); },
    fittedVideoWidth() { const ratio = Number(this.mediaState.ratio) || (this.mediaState.portrait ? 9 / 16 : 16 / 9); return this.height ? Math.max(0, this.height - 160) * ratio : 360; },
    videoWidth() { return Math.round(Math.max(300, Math.min(this.maxVideoWidth, this.preferredVideoWidth ?? this.fittedVideoWidth))); },
  },
  watch: { mediaVisible(visible) { if (!visible) this.drag = null; } },
  mounted() {
    this.measure();
    if (typeof ResizeObserver !== 'undefined') { this.observer = new ResizeObserver(this.measure); this.observer.observe(this.$refs.pane); }
    window.addEventListener('resize', this.measure);
  },
  beforeUnmount() { this.observer?.disconnect(); window.removeEventListener('resize', this.measure); },
  methods: {
    measure() { const rect = this.$refs.pane?.getBoundingClientRect(); this.width = rect?.width || 0; this.height = rect?.height || 0; if (this.stacked) this.drag = null; },
    setWidth(value) { this.preferredVideoWidth = Math.max(300, Math.min(this.maxVideoWidth, value)); },
    resizeKeydown(event) {
      if (event.isComposing || event.altKey || event.ctrlKey || event.metaKey) return;
      const step = event.shiftKey ? 40 : 20;
      const next = { ArrowLeft: this.videoWidth - step, ArrowRight: this.videoWidth + step, Home: 300, End: this.maxVideoWidth }[event.key];
      if (next != null) { event.preventDefault(); this.setWidth(next); }
    },
    startResize(event) {
      if (this.stacked || !this.mediaVisible || event.button > 0 || event.isPrimary === false) return;
      event.preventDefault(); event.currentTarget.focus(); event.currentTarget.setPointerCapture?.(event.pointerId);
      this.drag = { id: event.pointerId, x: event.clientX, width: this.videoWidth };
    },
    moveResize(event) { if (this.drag?.id === event.pointerId) this.setWidth(this.drag.width + event.clientX - this.drag.x); },
    endResize(event) { if (this.drag?.id !== event.pointerId) return; this.moveResize(event); this.drag = null; event.currentTarget.releasePointerCapture?.(event.pointerId); },
  },
};
</script>
<style scoped>
.collaboration-pane { display:flex; flex-direction:column; flex:1 1 0; min-width:0; min-height:0; overflow:hidden; }
.collaboration-pane.has-media:not(.is-stacked) { display:grid; grid-template-columns:var(--video-width) 12px minmax(0,1fr); }
.collaboration-media { display:none; min-width:0; min-height:0; flex:0 0 auto; height:auto; max-height:220px; overflow:auto; background:var(--admin-background,#f4f6f9); }
.has-media .collaboration-media { display:block; }
.has-media:not(.is-stacked) .collaboration-media { height:100%; max-height:none; }
.collaboration-communication { display:flex; flex-direction:column; min-width:0; min-height:0; flex:1 1 0; overflow:hidden; background:var(--el-bg-color,#fff); }
.collaboration-annotation { flex:0 0 0; height:0; min-height:0; min-width:0; overflow:hidden; }
.has-annotation .collaboration-annotation { flex:3 1 0; }
.has-annotation .collaboration-chat { flex:2 1 0; }
.collaboration-annotation:empty { display:none; }
.collaboration-chat { display:flex; flex-direction:column; flex:1 1 0; min-height:100px; overflow:hidden; }
.collaboration-divider { display:flex; align-items:center; justify-content:center; cursor:col-resize; touch-action:none; user-select:none; background:var(--admin-background,#f4f6f9); }
.collaboration-divider span { width:3px; height:40px; border-radius:3px; background:var(--el-border-color,#dcdfe6); }
.collaboration-divider:hover span,.collaboration-divider:focus-visible span,.is-resizing .collaboration-divider span { background:var(--el-color-primary,#409eff); }
.collaboration-divider:focus-visible { outline:2px solid var(--el-color-primary,#409eff); outline-offset:-2px; }
.is-stacked.has-media .collaboration-media { flex:0 1 36%; max-height:36%; }
/* Editing prioritizes the bounded editor while retaining the compact transcript header. */
.is-stacked.has-media.has-annotation .collaboration-media { flex:0 0 120px; max-height:120px; }
.is-stacked.has-media.is-preparing .collaboration-media { flex:0 1 auto; max-height:60%; }
.is-stacked.has-annotation .collaboration-annotation { flex:1 1 0; }
.is-stacked.has-annotation .collaboration-chat { flex:0 0 44px; min-height:44px; }
</style>
