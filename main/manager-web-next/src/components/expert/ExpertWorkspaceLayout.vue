<template>
  <section class="expert-layout" data-expert-workspace @keydown.esc="closePanels">
    <div class="mobile-panels"><button ref="clientsToggle" type="button" :aria-expanded="clientsOpen" aria-controls="expert-clients" @click="clientsOpen = !clientsOpen">{{ $t('expertWorkspace.openClients') }}</button><button ref="toolsToggle" type="button" :aria-expanded="toolsOpen" aria-controls="expert-tools" @click="toolsOpen = !toolsOpen">{{ $t('expertWorkspace.tools') }}</button></div>
    <aside id="expert-clients" class="expert-clients" :class="{ 'panel-open': clientsOpen }" :aria-label="$t('expertWorkspace.customerAccess')" data-workspace-region="contacts"><button class="panel-close" type="button" @click="closeClients">{{ $t('expertWorkspace.closeClients') }} ×</button><slot name="contacts" /></aside>
    <main class="expert-main" data-workspace-region="main"><slot /></main>
    <aside id="expert-tools" class="expert-tools" :class="{ 'panel-open': toolsOpen }" :aria-label="$t('expertWorkspace.tools')" data-workspace-region="tools"><header><h2>{{ $t('expertWorkspace.tools') }}</h2><button class="panel-close" type="button" @click="closeTools">{{ $t('expertWorkspace.closeTools') }} ×</button></header><div class="tools-scroll"><slot name="tools" /></div></aside>
  </section>
</template>
<script>
export default { name: 'ExpertWorkspaceLayout', data: () => ({ toolsOpen: false, clientsOpen: false }), methods: { closeTools() { this.toolsOpen = false; this.$refs.toolsToggle?.focus(); }, closeClients() { this.clientsOpen = false; this.$refs.clientsToggle?.focus(); }, closePanels() { if (this.toolsOpen) this.closeTools(); if (this.clientsOpen) this.closeClients(); } } };
</script>
<style scoped>
.expert-layout { --expert-accent:#087f7b; --expert-ink:#173b37; --expert-muted:#6c817c; display:grid; grid-template-columns:252px minmax(0,1fr) 292px; height:calc(100dvh - 150px); min-height:680px; color:var(--expert-ink); background:#f3f7f5; border:1px solid #dce7e3; border-radius:12px; overflow:hidden; position:relative; text-align:left; }
.expert-clients,.expert-tools,.expert-main { min-width:0; min-height:0; }.expert-clients,.expert-tools { background:#fff; display:flex; flex-direction:column; }.expert-clients { border-right:1px solid #dce7e3; }.expert-main { display:flex; flex-direction:column; overflow:auto; }.expert-tools { border-left:1px solid #dce7e3; }.expert-tools>header { padding:20px 18px; display:flex; align-items:center; justify-content:space-between; border-bottom:1px solid #e1eae6; }.expert-tools h2 { margin:0; font-size:18px; }.tools-scroll { padding:16px; overflow:auto; flex:1; min-height:0; overscroll-behavior:contain; }.mobile-panels,.panel-close { display:none; }button { font:inherit; cursor:pointer; }button:focus-visible { outline:3px solid #52b8ae; outline-offset:2px; }
@media(max-width:1280px) { .expert-layout { grid-template-columns:228px minmax(0,1fr); padding-bottom:48px; }.expert-tools { display:none; position:absolute; z-index:40; inset:0 0 48px auto; width:min(340px,100%); box-shadow:-12px 0 36px #123e3b25; }.expert-tools.panel-open { display:flex; }.mobile-panels { position:absolute; bottom:0; left:228px; right:0; display:flex; height:48px; border-top:1px solid #dce7e3; z-index:41; background:white; }.mobile-panels button { flex:1; border:0; background:white; color:var(--expert-accent); }.mobile-panels button:first-child { display:none; }.expert-tools .panel-close { display:block; border:0; color:var(--expert-accent); background:#edf5f1; padding:8px; border-radius:6px; font-size:11px; } }
@media(max-width:760px) { .expert-layout { display:flex; flex-direction:column; height:auto; min-height:calc(100dvh - 160px); border-radius:8px; padding:0 0 48px; overflow:hidden; }.expert-main { min-height:660px; }.mobile-panels { position:absolute; left:0; }.mobile-panels button:first-child { display:block; }.expert-clients { display:none; position:absolute; z-index:40; inset:0 auto 48px 0; width:min(320px,100%); box-shadow:12px 0 36px #123e3b25; }.expert-clients.panel-open { display:flex; }.expert-clients>.panel-close { display:block; padding:12px; border:0; border-bottom:1px solid #dce7e3; background:#edf5f1; color:var(--expert-accent); text-align:right; }.expert-tools { bottom:48px; } }

/* The shell owns viewport height; only the center and side panels scroll. */
.expert-layout { grid-template-columns:228px minmax(0,1fr) 280px; height:auto; min-height:0; flex:1 1 0; border-radius:10px; }
.expert-tools>header { padding:16px; }
@media(max-width:1280px) { .expert-layout { grid-template-columns:216px minmax(0,1fr); }.mobile-panels { left:216px; } }
@media(max-width:760px) {
  .expert-layout { height:auto; min-height:0; padding:0; }
  .expert-main { flex:1 1 0; min-height:0; }
  .mobile-panels { position:relative; inset:auto; display:flex; flex:0 0 44px; height:44px; width:100%; border-top:0; border-bottom:1px solid #dce7e3; z-index:41; }
  .mobile-panels button { min-height:44px; }
  .expert-clients { inset:44px auto 0 0; }
  .expert-tools { inset:44px 0 0 auto; }
}
</style>
