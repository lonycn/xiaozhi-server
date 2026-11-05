<template>
  <ExpertWorkspaceLayout v-if="unified" ref="layout" class="device-workspace-layout" :contacts-label="$t('chatHistory.sessions')" :contacts-toggle-label="$t('chatHistory.sessions')">
    <template #contacts><slot name="contacts" /></template>
    <slot />
    <template #tools><slot name="tools" /></template>
  </ExpertWorkspaceLayout>
  <div v-else class="workspace-grid"><slot name="contacts" /><slot /></div>
</template>

<script setup>
import { ref } from 'vue';
import ExpertWorkspaceLayout from './expert/ExpertWorkspaceLayout.vue';
defineProps({ unified: Boolean });
const layout = ref(null);
defineExpose({ closeTools: () => layout.value?.closeTools(), closeClients: () => layout.value?.closeClients() });
</script>

<style scoped>
.device-workspace-layout :deep(.expert-content){overflow:hidden}
.device-workspace-layout :deep(.conversation-panel),.device-workspace-layout :deep(.conversation-sidebar),.device-workspace-layout :deep(.hardware-history-panel){flex:1;min-height:0}
.device-workspace-layout :deep(.conversation-sidebar){border:0}
.device-workspace-layout :deep(.hardware-history-panel){min-height:0!important}
.device-workspace-layout :deep(.panel-heading),
.device-workspace-layout :deep(.history-detail-heading){padding:16px}
.device-workspace-layout :deep(.message-list),
.device-workspace-layout :deep(.history-messages){padding:16px}
.device-workspace-layout :deep(.history-context-bar),
.device-workspace-layout :deep(.hardware-readonly-note),
.device-workspace-layout :deep(.history-downloads){padding:12px 16px}
.device-workspace-layout :deep(.message-row),
.device-workspace-layout :deep(.history-message){width:100%;max-width:none;margin-inline:0;gap:8px}
.device-workspace-layout :deep(.message-content),
.device-workspace-layout :deep(.message-body){flex:1;max-width:none}
@media(max-width:760px){
  .device-workspace-layout :deep(.conversation-list){display:block;overflow-y:auto;overflow-x:hidden}
  .device-workspace-layout :deep(.conversation-item){width:100%;min-width:0}
  .device-workspace-layout :deep(.message-list){flex:1 1 0;min-height:0}
  .device-workspace-layout :deep(.panel-heading),
  .device-workspace-layout :deep(.history-detail-heading),
  .device-workspace-layout :deep(.message-list),
  .device-workspace-layout :deep(.history-messages),
  .device-workspace-layout :deep(.history-context-bar),
  .device-workspace-layout :deep(.hardware-readonly-note),
  .device-workspace-layout :deep(.history-downloads){padding:12px}
}
</style>
