<template>
  <span class="conversation-avatar" :class="[`avatar-${role}`, { 'avatar-own': own }]" role="img" :aria-label="label" :title="label">
    <span v-if="role === 'assistant'">AI</span><component v-else :is="icon" aria-hidden="true" />
  </span>
</template>

<script setup>
import { computed } from 'vue';
import { User, Service, Avatar, Tools, InfoFilled } from '@element-plus/icons-vue';
import { messageRole } from '../utils/messagePresentation.mjs';
const props = defineProps({ senderType: String, own: Boolean, label: String });
const role = computed(() => messageRole(props.senderType));
const icon = computed(() => ({ user: User, support_agent: Service, expert: Avatar, tool: Tools })[role.value] || InfoFilled);
</script>

<style scoped>
.conversation-avatar{display:grid;place-items:center;flex:0 0 36px;width:36px;height:36px;border-radius:10px;background:#edf0f5;color:#667085;font-size:var(--admin-font-xs);font-weight:600;line-height:1}
.conversation-avatar svg{width:20px;height:20px}
.avatar-user{background:#e4efff;color:#2563a8}.avatar-assistant{background:#e3f4ed;color:#247353}.avatar-support_agent{background:#fff0d9;color:#98610d}.avatar-expert{background:#eee7fa;color:#7550a6}.avatar-tool,.avatar-system{background:#edf0f5;color:#667085}
.conversation-avatar.avatar-own{background:var(--el-color-primary,#409eff);color:white}
</style>
