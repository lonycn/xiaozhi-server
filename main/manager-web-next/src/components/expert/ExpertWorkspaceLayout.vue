<template>
  <section class="expert-layout" :class="{ 'expert-layout--collaboration': collaboration, 'clients-collapsed': clientsCollapsed, 'is-compact': compact, 'tools-docked': toolsDocked, 'contacts-docked': contactsDocked }" data-expert-workspace @keydown.esc="closePanels">
    <div class="mobile-panels"><button ref="clientsToggle" type="button" :aria-expanded="contactsVisible" aria-controls="expert-clients" @click="toggleClients">{{ contactsToggleLabel || $t('expertWorkspace.openClients') }}</button><button v-show="!collaboration" ref="toolsToggle" type="button" :aria-expanded="toolsVisible" aria-controls="expert-tools" @click="toggleTools">{{ $t('expertWorkspace.tools') }}</button></div>
    <aside id="expert-clients" class="expert-clients" :class="{ 'panel-open': clientsOpen }" :aria-label="contactsLabel || $t('expertWorkspace.customerAccess')" data-workspace-region="contacts"><button class="panel-close" type="button" @click="closeClients">{{ $t(contactsLabel ? 'button.close' : 'expertWorkspace.closeClients') }} ×</button><slot name="contacts" /></aside>
    <main class="expert-main" data-workspace-region="main">
      <slot name="header" />
      <ExpertCollaborationPane v-if="collaboration" :media-state="mediaState" :annotation-visible="annotationVisible">
        <template #media><slot name="media" /></template>
        <template #annotation><slot name="annotation" /></template>
        <slot />
        <template #reply><slot name="reply" /></template>
      </ExpertCollaborationPane>
      <template v-else><div class="expert-content"><slot /></div><slot name="reply" /></template>
    </main>
    <div v-if="collaboration && !toolsDocked" class="tools-rail"><button ref="toolsRailToggle" class="tools-rail-toggle" type="button" :aria-label="$t('expertWorkspace.tools')" :aria-expanded="toolsVisible" aria-controls="expert-tools" @click="toggleTools"><span aria-hidden="true">‹</span><span class="tools-rail-label">{{ $t('expertWorkspace.tools') }}</span></button></div>
    <aside id="expert-tools" ref="toolsPanel" class="expert-tools" :class="{ 'panel-open': toolsOpen }" :aria-label="$t('expertWorkspace.tools')" data-workspace-region="tools"><header><h2>{{ $t('expertWorkspace.tools') }}</h2><button v-show="!toolsDocked" ref="toolsClose" class="panel-close" type="button" @click="closeTools">{{ $t('expertWorkspace.closeTools') }} ×</button></header><div class="tools-scroll"><slot name="tools" /></div></aside>
  </section>
</template>
<script>
import ExpertCollaborationPane from './ExpertCollaborationPane.vue';
export default {
  name: 'ExpertWorkspaceLayout', components: { ExpertCollaborationPane },
  props: { contactsLabel: String, contactsToggleLabel: String, collaboration: Boolean, mediaState: Object, annotationVisible: Boolean },
  data: () => ({ toolsOpen: false, clientsOpen: false, clientsCollapsed: false, layoutWidth: 0, observer: null }),
  computed: {
    compact() { return this.layoutWidth > 0 && this.layoutWidth < 1080; },
    contactsDocked() { return this.collaboration && !this.compact && !this.clientsCollapsed; },
    contactsVisible() { return this.collaboration && !this.compact ? !this.clientsCollapsed : this.clientsOpen; },
    mediaVisible() { return Boolean(this.mediaState?.visible ?? this.mediaState?.active); },
    toolsDocked() {
      const contactsWidth = !this.compact && !this.clientsCollapsed ? 216 : 0;
      const mainWidth = this.mediaVisible ? 760 : 600;
      return this.collaboration && this.layoutWidth >= contactsWidth + mainWidth + 280;
    },
    toolsVisible() { return this.toolsDocked || this.toolsOpen; },
  },
  watch: {
    async toolsDocked(docked) {
      this.toolsOpen = false;
      if (!docked && this.$refs.toolsPanel?.contains(document.activeElement)) {
        await this.$nextTick(); this.focusToolsToggle();
      }
    },
  },
  mounted() {
    if (!this.collaboration) return;
    this.measure(); if (typeof ResizeObserver !== 'undefined') { this.observer = new ResizeObserver(this.measure); this.observer.observe(this.$el); }
    window.addEventListener('resize', this.measure);
  },
  beforeUnmount() { this.observer?.disconnect(); window.removeEventListener('resize', this.measure); },
  methods: {
    measure() { this.layoutWidth = this.$el.getBoundingClientRect().width || window.innerWidth; },
    async toggleTools() { if (this.toolsDocked) return; this.toolsOpen = !this.toolsOpen; if (this.collaboration && this.toolsOpen) { this.clientsOpen = false; await this.$nextTick(); this.$refs.toolsClose?.focus(); } },
    toggleClients() { if (this.collaboration && !this.compact) this.clientsCollapsed = !this.clientsCollapsed; else this.clientsOpen = !this.clientsOpen; },
    focusToolsToggle() { (this.collaboration ? this.$refs.toolsRailToggle : this.$refs.toolsToggle)?.focus(); },
    closeTools() { this.toolsOpen = false; this.focusToolsToggle(); },
    closeClients() { this.clientsOpen = false; this.$refs.clientsToggle?.focus(); },
    closePanels() { if (this.toolsOpen) this.closeTools(); if (this.clientsOpen) this.closeClients(); },
  },
};
</script>
<style scoped>
.expert-layout { --expert-accent:var(--el-color-primary, #409eff); --expert-ink:var(--admin-text, #253247); --expert-muted:var(--el-text-color-secondary, #909399); display:grid; grid-template-columns:252px minmax(0,1fr) 292px; height:calc(100dvh - 150px); min-height:680px; color:var(--expert-ink); background:var(--admin-background, #f4f6f9); border:1px solid var(--admin-border, #e5e9f0); border-radius:12px; overflow:hidden; position:relative; text-align:left; }
.expert-clients,.expert-tools,.expert-main { min-width:0; min-height:0; }.expert-clients,.expert-tools { background:#fff; display:flex; flex-direction:column; }.expert-clients { border-right:1px solid var(--admin-border, #e5e9f0); }.expert-main { display:flex; flex-direction:column; overflow:auto; }.expert-tools { border-left:1px solid var(--admin-border, #e5e9f0); }.expert-tools>header { padding:20px 18px; display:flex; align-items:center; justify-content:space-between; border-bottom:1px solid var(--admin-border, #e5e9f0); }.expert-tools h2 { margin:0; font-size:var(--admin-font-lg); }.tools-scroll { padding:16px; overflow:auto; flex:1; min-height:0; overscroll-behavior:contain; }.mobile-panels,.panel-close { display:none; }button { font:inherit; cursor:pointer; }button:focus-visible { outline:3px solid var(--el-color-primary-light-3, #79bbff); outline-offset:2px; }
@media(max-width:1280px) { .expert-layout { grid-template-columns:228px minmax(0,1fr); padding-bottom:48px; }.expert-tools { display:none; position:absolute; z-index:40; inset:0 0 48px auto; width:min(340px,100%); box-shadow:-12px 0 36px #18233325; }.expert-tools.panel-open { display:flex; }.mobile-panels { position:absolute; bottom:0; left:228px; right:0; display:flex; height:48px; border-top:1px solid var(--admin-border, #e5e9f0); z-index:41; background:white; }.mobile-panels button { flex:1; border:0; background:white; color:var(--expert-accent); }.mobile-panels button:first-child { display:none; }.expert-tools .panel-close { display:block; border:0; color:var(--expert-accent); background:var(--el-color-primary-light-9, #ecf5ff); padding:8px; border-radius:6px; font-size:var(--admin-font-xs); } }
@media(max-width:760px) { .expert-layout { display:flex; flex-direction:column; height:auto; min-height:calc(100dvh - 160px); border-radius:8px; padding:0 0 48px; overflow:hidden; }.expert-main { min-height:660px; }.mobile-panels { position:absolute; left:0; }.mobile-panels button:first-child { display:block; }.expert-clients { display:none; position:absolute; z-index:40; inset:0 auto 48px 0; width:min(320px,100%); box-shadow:12px 0 36px #18233325; }.expert-clients.panel-open { display:flex; }.expert-clients>.panel-close { display:block; padding:12px; border:0; border-bottom:1px solid var(--admin-border, #e5e9f0); background:var(--el-color-primary-light-9, #ecf5ff); color:var(--expert-accent); text-align:right; }.expert-tools { bottom:48px; } }

/* The shell owns viewport height; only the center and side panels scroll. */
.expert-main { overflow:hidden; }
.expert-content { display:flex; flex:1 1 0; flex-direction:column; min-height:0; overflow:auto; overscroll-behavior:contain; }
.expert-layout { grid-template-columns:228px minmax(0,1fr) 280px; height:auto; min-height:0; flex:1 1 0; border-radius:10px; }
.expert-tools>header { padding:16px; }
@media(max-width:1280px) { .expert-layout { grid-template-columns:216px minmax(0,1fr); }.mobile-panels { left:216px; } }
@media(max-width:760px) {
  .expert-layout { height:auto; min-height:0; padding:0; }
  .expert-main { flex:1 1 0; min-height:0; }
  .mobile-panels { position:relative; inset:auto; display:flex; flex:0 0 44px; height:44px; width:100%; border-top:0; border-bottom:1px solid var(--admin-border, #e5e9f0); z-index:41; }
  .mobile-panels button { min-height:44px; }
  .expert-clients { inset:44px auto 0 0; }
  .expert-tools { inset:44px 0 0 auto; }
}
/* Collaboration uses the available workspace width, including the admin navigation. */
.expert-layout--collaboration { --workspace-toolbar-height:32px; --clients-width:216px; --tools-width:36px; grid-template-columns:var(--clients-width) minmax(0,1fr) var(--tools-width); grid-template-rows:var(--workspace-toolbar-height) minmax(0,1fr); padding:0; }
.expert-layout--collaboration .mobile-panels { position:static; grid-column:1/-1; grid-row:1; display:flex; height:var(--workspace-toolbar-height); width:auto; border-top:0; border-bottom:1px solid var(--admin-border,#e5e9f0); z-index:3; }
.expert-layout--collaboration .mobile-panels button { display:block; min-height:var(--workspace-toolbar-height); flex:0 1 auto; padding:0 18px; border:0; background:white; color:var(--expert-accent); }
.expert-layout--collaboration .expert-main { grid-column:2; grid-row:2; }
.expert-layout--collaboration .expert-clients { grid-column:1; grid-row:2; display:flex; }
.expert-layout--collaboration .expert-tools { display:none; position:absolute; z-index:40; inset:var(--workspace-toolbar-height) 0 0 auto; width:min(360px,100%); box-shadow:-12px 0 36px #18233325; }
.expert-layout--collaboration .expert-tools.panel-open { display:flex; }
.expert-layout--collaboration .expert-tools .panel-close { display:block; }
.expert-layout--collaboration.tools-docked { --tools-width:280px; }
.expert-layout--collaboration.tools-docked .expert-tools { display:flex; position:static; grid-column:3; grid-row:2; width:auto; box-shadow:none; z-index:auto; }
.tools-rail { grid-column:3; grid-row:2; min-height:0; border-left:1px solid var(--admin-border,#e5e9f0); background:#fff; }
.tools-rail-toggle { display:flex; flex-direction:column; align-items:center; gap:10px; width:100%; height:100%; padding:16px 0; border:0; background:var(--el-color-primary-light-9,#ecf5ff); color:var(--expert-accent); }
.tools-rail-toggle:hover { background:var(--el-color-primary-light-8,#d9ecff); }
.tools-rail-toggle>span:first-child { font-size:20px; line-height:1; }
.tools-rail-label { writing-mode:vertical-rl; font-size:var(--admin-font-sm,14px); letter-spacing:2px; }
.expert-layout--collaboration.clients-collapsed,.expert-layout--collaboration.is-compact { --clients-width:0px; }
.expert-layout--collaboration.clients-collapsed:not(.is-compact) .expert-clients { display:none; }
.expert-layout--collaboration.is-compact .expert-clients { display:none; position:absolute; z-index:40; inset:var(--workspace-toolbar-height) auto 0 0; width:min(320px,100%); box-shadow:12px 0 36px #18233325; }
.expert-layout--collaboration.is-compact .expert-clients.panel-open { display:flex; }
.expert-layout--collaboration.is-compact .expert-clients>.panel-close { display:block; }
/* The desktop contacts toolbar occupies only its rail; conversation and tools use the full height. */
.expert-layout--collaboration.contacts-docked .mobile-panels { grid-column:1; }
.expert-layout--collaboration.contacts-docked .expert-main,
.expert-layout--collaboration.contacts-docked .tools-rail,
.expert-layout--collaboration.contacts-docked.tools-docked .expert-tools { grid-row:1 / -1; }
.expert-layout--collaboration.contacts-docked .expert-tools { top:0; }
@media(max-width:760px) { .expert-layout--collaboration { display:grid; }.expert-layout--collaboration .expert-clients { position:absolute; } }
</style>
