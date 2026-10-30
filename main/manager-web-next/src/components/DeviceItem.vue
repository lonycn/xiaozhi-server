<template>
  <article class="device-item">
    <div class="agent-identity">
      <span class="agent-avatar" aria-hidden="true"><LegacyIcon class="el-icon-user" /></span>
      <div class="agent-identity-copy">
        <el-tooltip :content="device.systemPrompt || device.agentName" placement="top" effect="light" popper-class="device-item-tooltip">
          <h3 class="device-item-title">{{ device.agentName }}</h3>
        </el-tooltip>
        <div v-if="tags.length" class="agent-tags"><el-tag v-for="tag in tags" :key="tag" size="small" effect="plain">{{ tag }}</el-tag></div>
        <span class="agent-device-count">{{ $t('home.deviceManagement') }} · {{ device.deviceCount || 0 }}</span>
      </div>
    </div>
    <div class="agent-models">
      <div class="device-name"><span>{{ $t('home.languageModel') }}</span><strong>{{ device.llmModelName || '—' }}</strong></div>
      <div class="device-name"><span>{{ $t('home.voiceModel') }}</span><strong>{{ device.ttsModelName || '—' }}<template v-if="device.ttsVoiceName"> · {{ device.ttsVoiceName }}</template></strong></div>
    </div>
    <div class="version-info"><span>{{ $t('home.lastConversation') }}</span><span>{{ formattedLastConnectedTime }}</span></div>
    <div class="agent-actions">
      <el-button link type="primary" @click="handleConfigure">{{ $t('home.configureRole') }}</el-button>
      <el-button v-if="featureStatus.voiceprintRecognition" link type="primary" @click="handleVoicePrint">{{ $t('home.voiceprintRecognition') }}</el-button>
      <el-button link type="primary" @click="handleDeviceManage">{{ $t('home.deviceManagement') }}</el-button>
      <el-tooltip :disabled="device.memModelId !== 'Memory_nomem'" :content="$t('home.enableMemory')" placement="top">
        <span><el-button link type="primary" :disabled="device.memModelId === 'Memory_nomem'" @click="handleChatHistory">{{ $t('home.chatHistory') }}</el-button></span>
      </el-tooltip>
      <el-button class="agent-delete" link type="danger" :aria-label="$t('common.delete')" :title="$t('common.delete')" @click.stop="handleDelete"><LegacyIcon class="el-icon-delete" /></el-button>
    </div>
  </article>
</template>

<script>
import i18n from '@/i18n';

export default {
  emits: ["delete","chat-history"],
  name: 'DeviceItem',
  props: {
    device: { type: Object, required: true },
    featureStatus: { 
      type: Object, 
      default: () => ({
        voiceprintRecognition: false,
        voiceClone: false,
        knowledgeBase: false
      })
    }
  },
  data() {
    return { switchValue: false }
  },
  computed: {
    formattedLastConnectedTime() {
      if (!this.device.lastConnectedAt) return this.$t('home.noConversation');

      const lastTime = new Date(this.device.lastConnectedAt);
      const now = new Date();
      const diffMinutes = Math.floor((now - lastTime) / (1000 * 60));

      if (diffMinutes <= 1) {
        return this.$t('home.justNow');
      } else if (diffMinutes < 60) {
        return this.$t('home.minutesAgo', { minutes: diffMinutes });
      } else if (diffMinutes < 24 * 60) {
        const hours = Math.floor(diffMinutes / 60);
        const minutes = diffMinutes % 60;
        return this.$t('home.hoursAgo', { hours, minutes });
      } else {
        return this.device.lastConnectedAt;
      }
    },
    tags() {
      if (!this.device.tags) return [];
      return this.device.tags.map((tag) => tag.tagName);
    }
  },
  methods: {
    handleDelete() {
      this.$emit('delete', this.device)
    },
    handleConfigure() {
      this.$router.push({ path: '/role-config', query: { agentId: this.device.agentId } });
    },
    handleVoicePrint() {
      this.$router.push({ path: '/voice-print', query: { agentId: this.device.agentId } });
    },
    handleDeviceManage() {
      this.$router.push({ path: '/device-management', query: { agentId: this.device.agentId } });
    },
    handleChatHistory() {
      if (this.device.memModelId === 'Memory_nomem') {
        return
      }
      this.$emit('chat-history', { agentId: this.device.agentId, agentName: this.device.agentName })
    }
  },
}
</script>
<style lang="scss" scoped>
.device-item { width: 100%; display: grid; grid-template-columns: minmax(170px, 1.1fr) minmax(200px, 1.25fr) minmax(110px, .65fr) minmax(240px, 1.2fr); align-items: center; gap: 20px; padding: 24px 20px; box-sizing: border-box; border-bottom: 1px solid #edf0f4; background: #fff; text-align: left; }
.device-item:last-child { border-bottom: 0; }
.device-item:hover { background: #fafbfd; }
.agent-identity { display: flex; gap: 12px; align-items: center; min-width: 0; }
.agent-avatar { width: 44px; height: 44px; flex: 0 0 44px; border-radius: 8px; background: #edf4ff; color: #3b76dc; display: grid; place-items: center; font-size: 24px; }
.agent-identity-copy { min-width: 0; }
.device-item-title { margin: 0 0 6px; color: #263445; font-size: 15px; line-height: 1.4; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.agent-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-bottom: 6px; }
.agent-device-count { font-size: 12px; color: #8b95a5; }
.agent-models { min-width: 0; }
.device-name { display: flex; align-items: baseline; gap: 10px; margin: 5px 0; font-size: 12px; }
.device-name span { color: #8b95a5; flex-shrink: 0; }
.device-name strong { font-weight: 400; color: #566477; overflow-wrap: anywhere; }
.version-info { display: flex; flex-direction: column; gap: 8px; font-size: 12px; color: #65758a; }
.version-info > :first-child { color: #8b95a5; }
.agent-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: 8px 14px; }
.agent-actions .el-button { margin: 0; font-size: 12px; }
.agent-delete { padding: 4px; font-size: 15px !important; }
@media (max-width: 1200px) { .device-item { grid-template-columns: minmax(180px, 1fr) minmax(180px, 1.1fr) minmax(210px, 1fr); } .version-info { grid-column: 2; grid-row: 2; flex-direction: row; } .agent-actions { grid-column: 3; grid-row: 1 / span 2; } }
@media (max-width: 760px) { .device-item { grid-template-columns: 1fr; gap: 14px; padding: 20px 16px; } .version-info, .agent-actions { grid-column: auto; grid-row: auto; } .agent-actions { justify-content: flex-start; } }
</style>

<style>
.device-item-tooltip {
  max-height: 60vh !important;
  max-width: 400px !important;
  overflow-y: auto !important;
  scrollbar-width: thin;
  word-break: break-word;
}

.device-item-tooltip .popper__arrow {
  display: none !important;
}

.device-item-tooltip[x-placement^="top"] .popper__arrow {
  border-top-color: transparent !important;
}

.device-item-tooltip[x-placement^="bottom"] .popper__arrow {
  border-bottom-color: transparent !important;
}
</style>
