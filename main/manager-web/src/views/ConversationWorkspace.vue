<template>
  <div class="conversation-workspace">
    <ConversationWorkspaceFrame ref="workspaceLayout" :unified="hardwareOnly" :class="{ 'with-source-filters': canBrowseSources, 'with-hardware-filters': canBrowseSources && sourceFilter === 'hardware' }">
      <template #contacts><aside class="conversation-sidebar" aria-label="会话列表">
        <div v-if="canBrowseSources" class="conversation-source-filters">
          <div class="conversation-source-tabs" role="tablist" :aria-label="$t('conversationSource.label')">
            <button v-for="source in (hardwareOnly ? ['hardware'] : ['all','app','web','hardware'])" :id="`source-tab-${source}`" :key="source" type="button" role="tab" :aria-selected="sourceFilter === source" aria-controls="workspace-conversation-list" :tabindex="sourceFilter === source ? 0 : -1" :disabled="sourceBusy" @click="changeSource(source)" @keydown="sourceTabKeydown($event, source)">{{ $t(`conversationSource.${source}`) }}</button>
          </div>
          <template v-if="sourceFilter === 'hardware'">
            <label for="conversation-agent">{{ $t('conversationSource.agent') }}</label><select id="conversation-agent" :value="agentFilter" :disabled="sourceBusy" @change="changeHardwareFilter($event.target.value, '')"><option value="">{{ $t('conversationSource.allAgents') }}</option><option v-for="agent in sourceOptions.agents" :key="agent.id" :value="agent.id">{{ agent.agent_name }}</option></select>
            <label for="conversation-device">{{ $t('chatHistory.deviceFilter') }}</label><select id="conversation-device" :value="deviceFilter" :disabled="sourceBusy" @change="changeHardwareFilter(agentFilter, $event.target.value)"><option value="">{{ $t('chatHistory.allDevices') }}</option><option v-for="mac in sourceDevices" :key="mac" :value="mac">{{ mac }}</option></select>
          </template>
          <p v-if="sourceOptionsError" class="source-options-error" role="alert">{{ $t('conversationSource.optionsFailed') }} <button class="text-button" @click="loadSourceOptions">{{ $t('chatHistory.retry') }}</button></p>
        </div>
        <div class="sidebar-heading"><span>我的会话</span><button class="icon-button" aria-label="刷新会话列表" :disabled="loadingList || sourceBusy" @click="loadConversations()"><LegacyIcon class="el-icon-refresh" /></button></div>
        <button v-if="sourceFilter !== 'hardware' && sourceFilter !== 'app'" class="new-conversation" :disabled="sourceBusy" @click="openCreate"><LegacyIcon class="el-icon-plus" /> 新建对话</button>
        <p v-if="loadingList && !conversations.length" class="sidebar-empty" role="status">正在读取会话…</p>
        <p v-else-if="!conversations.length" class="sidebar-empty">{{ $t('conversationSource.empty') }}</p>
        <p v-if="legacySelected && error" class="source-options-error" role="alert">{{ error }}</p>
        <div id="workspace-conversation-list" class="conversation-list" :role="canBrowseSources ? 'tabpanel' : null" :aria-labelledby="canBrowseSources ? `source-tab-${sourceFilter}` : null">
          <button v-for="item in conversations" :key="item.row_key || item.id" :disabled="sourceBusy" class="conversation-item" :class="{ selected: indexSelected(item) }" :aria-current="indexSelected(item) ? 'true' : null" @click="selectIndexItem(item)">
            <LegacyIcon class="el-icon-chat-line-square" /><span><strong>{{ item.title || $t('home.chatHistory') }}</strong><small>{{ formatDate(item.updated_at || item.created_at, true, item.source === 'hardware') }}<template v-if="canBrowseSources"> · {{ $t(`conversationSource.${['app','web','hardware'].includes(item.source) ? item.source : 'all'}`) }}</template></small><small v-if="item.source === 'hardware'">{{ item.agent_name }}<template v-if="item.mac_address"> · {{ item.mac_address }}</template></small></span>
          </button>
          <button v-if="listHasMore" class="text-button" :disabled="loadingList" @click="loadConversations(true)">加载更多会话</button>
        </div>
        <div class="sidebar-note"><span class="connection-dot" /> {{ deviceHistoryOnly ? (hardwareOnly ? $t('deviceDebug.environment') : '实机记录 · 只读') : '开发环境独立存储' }}{{ hardwareOnly ? ' · 北京时间' : '' }}</div>
      </aside></template>
      <AgentChatHistory ref="legacyHistory" @history-availability="legacyHistoryReady = $event" v-if="legacySelected" class="hardware-history-panel" embedded :history-agent-id="legacySelected.agent_id" :history-agent-name="legacySelected.agent_name" :history-session="{ sessionId: legacySelected.id, title: legacySelected.title, createdAt: legacySelected.updated_at }" :history-device="deviceFilter" />
      <section v-else-if="!hardwareOnly || selected?.source === 'hardware'" class="conversation-panel" aria-label="对话内容">
        <div class="panel-heading"><div><h1>{{ selected ? selected.title : '把现场问题留在同一段对话里' }}</h1><p>{{ selected ? '消息保存后，刷新页面仍可继续查看' : '文字记录、位置与后续服务，将在这里衔接' }}</p></div><div v-if="selected" class="panel-heading-actions"><button v-if="!hardwareOnly && selected.source === 'hardware'" type="button" class="history-search-entry" :disabled="loadingMessages" @click="openHistorySearch"><LegacyIcon class="el-icon-search" />{{ $t('historyFinder.title') }}</button><button class="icon-button" aria-label="刷新当前对话" :disabled="loadingMessages || sending" @click="selectConversation(selected)"><LegacyIcon class="el-icon-refresh" /></button></div></div>
        <div v-if="historyContextActive" class="history-context-bar"><span>{{ $t('historyFinder.historical') }}</span><button type="button" :disabled="loadingMessages" @click="selectConversation(selected)">{{ $t('historyFinder.latest') }}</button></div>
        <div v-if="error" class="workspace-error" role="alert"><LegacyIcon class="el-icon-warning-outline" /><span>{{ error }}</span><button aria-label="关闭错误提示" @click="error = ''">×</button></div>
        <div ref="messageList" class="message-list" :class="{ 'hardware-messages': selected?.source === 'hardware' }" aria-live="polite" :aria-busy="loadingMessages || loadingOlderMessages" @scroll.passive="messageListScrolled">
          <div v-if="selected?.source === 'hardware' && messages.length" class="message-history-status" role="status"><button v-if="messageHasMore" class="text-button" :disabled="loadingMessages || loadingOlderMessages" @click="loadOlderMessages">{{ $t(loadingOlderMessages ? 'chatHistory.loading' : historyError ? 'chatHistory.earlierFailed' : 'chatHistory.earlierMessages') }}</button><span v-else>{{ $t('chatHistory.allMessagesLoaded') }}</span></div>
          <div v-if="!selected" class="welcome-state"><span class="welcome-icon"><LegacyIcon class="el-icon-chat-dot-round" /></span><h2>从一个问题开始</h2><p>记录故障现象，保留每一次沟通。<br />选择左侧会话，或新建一段对话。</p><button v-if="sourceFilter !== 'app'" class="primary-button" @click="openCreate">新建对话 <LegacyIcon class="el-icon-right" /></button></div>
          <div v-else-if="loadingMessages && !messages.length" class="welcome-state" role="status"><LegacyIcon class="el-icon-loading" /><p>正在读取聊天记录…</p></div>
          <div v-else-if="!messages.length" class="welcome-state"><span class="welcome-icon"><LegacyIcon class="el-icon-edit-outline" /></span><h2>这段对话还没有消息</h2><p>可以描述设备、故障表现和已尝试的操作。</p></div>
          <article v-for="message in messages" :key="message.id" :data-message-id="message.id" tabindex="-1" class="message-row chat-message" :class="['sender-' + messageRole(message.sender_type || 'user'), { incoming: message.sender_type && message.sender_type !== 'user', 'own-message': messageIsOwn(message), 'history-target': highlightedMessageId === message.id }]"><div class="message-content thread-body"><div class="message-meta thread-meta">{{ $t(`support.${senderKey(message.sender_type || 'user')}`) }} <span v-if="messageIsOwn(message)" class="message-self-label">{{ $t('chatMessage.me') }}</span><span>{{ formatDate(message.created_at, true, selected?.source === 'hardware') }}</span></div><div class="message-bubble thread-bubble"><p v-if="message.sender_type === 'user' && !message.parts.length && ['transcribing','failed'].includes(message.status)">{{ $t('realtime.input_' + message.status) }}</p><template v-for="(part, index) in message.parts" :key="index"><p v-if="part.type === 'text'">{{ part.text }}</p><AttachmentPart v-else-if="['image', 'video', 'audio', 'file'].includes(part.type) && part.attachment_id" :part="part" :scope-key="attachmentScope" :can-annotate="!!selected && selected.state !== 'RESOLVED' && !sending && !loadingMessages" @annotate="openAnnotation" @snapshot="openAnnotationSnapshot" /><div v-else-if="part.type === 'location'" class="location-card"><LegacyIcon class="el-icon-location-outline" /> 共享位置：{{ part.latitude }}, {{ part.longitude }}<small>{{ part.coordinate_system }} · 精度 {{ part.accuracy_m }} 米</small></div></template></div><div v-if="message.sender_type === 'assistant'" class="message-saved thread-status">{{ $t('ai.' + (message.status || 'completed')) }} <AnswerSpeech v-if="selected.source !== 'hardware' && speechAvailable && message.status === 'completed' && message.parts.some(part => part.type === 'text' && part.text?.trim())" :conversation-id="selected.id" :message="message" :identity="attachmentIdentity" :blocked="speechBlocked" /> <span v-if="message.status === 'failed'">{{ $t(aiFailureKey(runFor(message)?.error_code || message.error_code)) }}</span> <template v-if="selected.source !== 'hardware' && !humanActive && selected?.state === 'AI_ACTIVE' && runFor(message)"><button v-if="['queued', 'running', 'streaming'].includes(runFor(message).status)" :disabled="runBusy[`${selected.id}:${runFor(message).run_id}`]" @click="runAction(runFor(message), 'cancel')">{{ $t('ai.stop') }}</button><button v-if="['failed', 'canceled'].includes(runFor(message).status) && runFor(message).error_code !== 'OMNI_AUDIO_LIMIT'" :disabled="runBusy[`${selected.id}:${runFor(message).run_id}`]" @click="runAction(runFor(message), 'retry')">{{ $t('ai.retry') }}</button></template></div><div v-else class="message-saved thread-status"><LegacyIcon class="el-icon-check" /> 已保存 <span v-if="realtimeInjections[message.id]"> · {{ $t('realtime.injection_' + realtimeInjections[message.id].status) }}</span></div></div><MessageAvatar class="sender-avatar thread-avatar" :sender-type="message.sender_type || 'user'" :own="messageIsOwn(message)" :label="$t(`support.${senderKey(message.sender_type || 'user')}`)" /></article>
        </div>
        <footer v-if="selected?.source !== 'hardware'" class="composer-area">
          <div v-if="selected" class="support-controls" aria-live="polite">
            <span>{{ $t('support.' + (selected.state || 'AI_ACTIVE')) }}</span>
            <span v-if="supportRequest" :title="supportRequest.request_id"> · {{ $t('support.' + supportRequest.status) }}</span>
            <el-button v-if="!humanActive && selected.state !== 'RESOLVED'" :loading="supportBusy" @click="handoff('support_agent')">{{ $t('support.handoff') }}</el-button><el-button v-if="!humanActive && selected.state !== 'RESOLVED'" :loading="supportBusy" @click="handoff('expert')">{{ $t('conversationManagement.requestExpert') }}</el-button>
            <el-button v-if="supportRequest?.status === 'queued'" :loading="supportBusy" @click="cancelHandoff">{{ $t('support.cancel') }}</el-button>
            <el-button v-if="selected.state === 'RESOLVED'" :loading="supportBusy" @click="resumeAi">{{ $t('support.resume') }}</el-button>
          </div>
          <p v-if="mediaNotices[selected?.id]" class="media-snapshot-notice" role="status">{{ $t('media.snapshotNotice') }}</p>
          <ReadonlySkillPicker v-if="selected && skillsAvailable" :conversation-id="selected.id" :identity="attachmentIdentity" />
          <ToolResults v-if="selected" :conversation-id="selected.id" :identity="attachmentIdentity" :refresh-key="toolResultsRefresh" />
          <AgentToolStatus v-if="selected" :conversation-id="selected.id" :identity="attachmentIdentity" />
          <div class="capability-note"><LegacyIcon class="el-icon-info" /><span>{{ $t(aiAvailable === false ? 'ai.unavailable' : 'ai.capability') }}</span></div>
          <AIRealtimeOverlay ref="aiRealtime" :conversation-id="selected?.id || ''" :identity="attachmentIdentity" :allowed="realtimeAllowed" @state="realtimeChanged" @history="pollConversation" />
          <MediaSessionOverlay :conversation-id="selected?.id || ''" :request-id="supportRequest?.status === 'active' ? supportRequest.request_id : ''" :identity="attachmentIdentity" :user-id="mediaUserId" :writable="selected?.state === 'HUMAN_ACTIVE'" @changed="humanMediaChanged" @frame="openLiveFrame" />
          <AnnotationEditor ref="annotationEditor" :conversation-id="selected?.id || ''" :scope-key="attachmentScope" :identity="attachmentIdentity" :writable="!!selected && selected.state !== 'RESOLVED'" :disabled="sending || loadingMessages" @ready="stageAnnotation" />
          <p v-if="realtimeBusy && attachmentRows.length" role="status">{{ $t('realtime.attachmentDraft') }}</p>
          <AttachmentComposer :key="attachmentScope" v-model="attachmentRows" :conversation-id="selected?.id || ''" :identity="attachmentIdentity" :disabled="sending || loadingMessages || realtimeBusy || selected?.state === 'RESOLVED'" />
          <form class="message-composer" @submit.prevent="sendMessage"><textarea v-model="draft" aria-label="消息内容" :disabled="!selected || realtimeMaterialBusy || sending || selected.state === 'RESOLVED'" maxlength="8000" rows="3" :placeholder="selected ? '描述你的问题…' : '新建或选择会话后，即可开始记录'" @keydown="composerKeydown" /><div class="composer-bottom"><span>{{ draft.length }}/8000 <span class="keyboard-hint">· Enter 发送，Shift + Enter 换行</span></span><button type="submit" class="primary-button send-button" :disabled="!selected || realtimeMaterialBusy || (!draft.trim() && (composerTextOnly || !attachmentRows.length)) || (!composerTextOnly && !attachmentsReady) || sending || loadingMessages || selected.state === 'RESOLVED'"><LegacyIcon :class="sending ? 'el-icon-loading' : 'el-icon-position'" /> {{ sending ? '保存中' : '发送消息' }}</button></div></form>
        </footer>
        <footer v-else class="hardware-readonly-note">{{ $t('conversationSource.readonly') }}</footer>
      </section>
      <section v-else class="conversation-panel"><div class="welcome-state"><h2>{{ $t('conversationManagement.devices') }}</h2><p>{{ $t('conversationSource.readonly') }}</p></div></section>
      <template #tools>
        <section class="device-context"><h3>{{ $t('expertWorkspace.context') }}</h3><template v-if="hardwareSelection"><strong>{{ hardwareSelection.title || $t('home.chatHistory') }}</strong><dl><div v-if="hardwareSelection.agent_name"><dt>{{ $t('addAgentDialog.agentName') }}</dt><dd>{{ hardwareSelection.agent_name }}</dd></div><div v-if="hardwareSelection.mac_address"><dt>{{ $t('device.macAddress') }}</dt><dd>{{ hardwareSelection.mac_address }}</dd></div><div><dt>{{ $t('expertWorkspace.conversation') }}</dt><dd>{{ hardwareSelection.id }}</dd></div></dl></template><p>{{ $t('conversationSource.readonly') }}</p></section>
        <DeviceDebugTool :agent-id="hardwareSelection?.agent_id" :mac-address="hardwareSelection?.mac_address" :token="deviceDebugToken" :identity="attachmentIdentity" @submitted="deviceInputSubmitted" />
        <HistorySearchTool :disabled="!canSearchHardwareHistory" @open="openHistorySearch" />
      </template>
    </ConversationWorkspaceFrame>
    <HistorySearchDrawer v-if="historyVisible && selected?.source === 'hardware'" :key="attachmentScope" :title="selected.title" :scope-key="attachmentScope" :load-page="searchHistoryPage" :locating="loadingMessages" @close="historyVisible = false" @locate="locateHistoryMessage" />
    <el-dialog title="新建对话" v-model="createVisible" width="min(440px, 92vw)" :close-on-click-modal="!creating" @opened="focusTitle">
      <label class="title-label" for="conversation-title">对话名称</label><el-input id="conversation-title" ref="titleInput" v-model="newTitle" placeholder="例如：3 号库压缩机运行异响" maxlength="200" :disabled="creating" @keyup.enter="createConversation" /><p class="dialog-hint">使用设备或问题命名，方便以后找到。</p>
      <p v-if="createError" class="create-error" role="alert">{{ createError }}</p>
      <template #footer><el-button :disabled="creating" @click="createVisible = false">取消</el-button><el-button type="primary" :loading="creating" :disabled="!newTitle.trim()" @click="createConversation">创建对话</el-button></template>
    </el-dialog>
  </div>
</template>

<script>
import MessageAvatar from '../components/MessageAvatar.vue';
import { messageRole, isOwnMessage } from '../utils/messagePresentation.mjs';
import HistorySearchDrawer from '../components/HistorySearchDrawer.vue';
import HistorySearchTool from '../components/HistorySearchTool.vue';
import DeviceDebugTool from '../components/DeviceDebugTool.vue';
import ConversationWorkspaceFrame from '../components/ConversationWorkspaceFrame.vue';
import { readConversationHistoryPage } from '../utils/historySearch.mjs';
import AgentToolStatus from '../components/AgentToolStatus.vue';
import AgentChatHistory from './AgentChatHistory.vue';
import AnswerSpeech from '../components/AnswerSpeech.vue';
import ReadonlySkillPicker from '../components/ReadonlySkillPicker.vue';
import { readonlySkillsAvailable } from '../apis/module/readonlySkillsClient.mjs';
import answerSpeechApi from '../apis/module/answerSpeech';
import { answerSpeechAvailable } from '../apis/module/answerSpeechClient.mjs';
import answerSpeechPlayback from '../speech/answerSpeech.mjs';
import ToolResults from '../components/ToolResults.vue';
import AIRealtimeOverlay from '../components/AIRealtimeOverlay.vue';
import { realtimeAuthorityKey } from '../realtime/identity.mjs';
import { aiFailureKey } from '../utils/aiErrors.mjs';
import { REALTIME_TERMINAL } from '../realtime/session.mjs';
import MediaSessionOverlay from '../components/MediaSessionOverlay.vue';
import AnnotationEditor from '../components/AnnotationEditor.vue';
import AttachmentComposer from '../components/AttachmentComposer.vue';
import AttachmentPart from '../components/AttachmentPart.vue';
import store from '../store';
import { mixedMessageParts, attachmentRowsReady, attachmentIdentity } from '../utils/attachments.mjs';
import conversationApi from '../apis/module/conversation';
import supportApi from '../apis/module/support';
import { readSse, mergeMessages, projectAiEvent, abortableDelay } from '../utils/aiStream.mjs';
import { senderKey } from '../utils/support.mjs';
import { formatBeijingTime } from '../utils/date';

function cursorInteger(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return BigInt(value);
  if (typeof value === 'string' && /^[0-9]+$/.test(value)) return BigInt(value);
  throw new Error('历史记录游标异常，请刷新后重试');
}


export default {
  name: 'ConversationWorkspace',
  props: { hardwareOnly: Boolean },
  components: { MessageAvatar, DeviceDebugTool, ConversationWorkspaceFrame, HistorySearchTool, HistorySearchDrawer, AgentChatHistory, ReadonlySkillPicker, AnswerSpeech, ToolResults, AgentToolStatus, AttachmentComposer, AttachmentPart, AnnotationEditor, MediaSessionOverlay, AIRealtimeOverlay },
  data() {
    return { legacyHistoryReady: false, historyVisible: false, historyContextActive: false, highlightedMessageId: '', lastMessageScrollTop: 0, messageBefore: '1', messageHasMore: false, loadingOlderMessages: false, historyError: false, legacySelected: null, sourceFilter: this.hardwareOnly ? 'hardware' : 'all', agentFilter: '', deviceFilter: '', sourceOptions: { agents: [], devices: [] }, sourceOptionsError: false, indexVersion: 0, indexController: new AbortController(), sourceOptionController: null, skillsAvailable: false, speechAvailable: false, speechCapabilityController: null, authorizationGeneration: 0, toolResultsRefresh: 0, environmentLabel: import.meta.env.VITE_ENV_LABEL || '开发预览', identityEpoch: 0, identityController: new AbortController(), attachmentDrafts: {}, realtimeState: null, humanMedia: null, mediaNotices: {}, conversations: [], selected: null, messages: [], drafts: {}, pending: {}, error: '', createError: '', loadingList: false, loadingMessages: false, sending: false, createVisible: false, creating: false, newTitle: '', listCursor: 0, listHasMore: false, selectionVersion: 0, supportRequest: null, supportBusy: false, handoffPending: {}, pollTimer: null, messageCursor: '0', eventCursor: '0', pollBusy: false, disposed: false, runs: [], aiAvailable: null, streamController: null, streamCursor: '0', streamProjections: {}, selectionController: null, runBusy: {} };
  },
  computed: {
    deviceDebugToken() { try { return JSON.parse(store.getters.getToken || '{}').token || ''; } catch { return ''; } },
    hardwareSelection() { return this.legacySelected || (this.selected?.source === 'hardware' ? this.selected : null); },
    canSearchHardwareHistory() { return !this.disposed && (this.legacySelected ? this.legacyHistoryReady : this.selected?.source === 'hardware' && !this.loadingMessages); },
    deviceHistoryOnly() { return import.meta.env.VITE_DEVICE_HISTORY_ONLY === 'true'; },
    canBrowseSources() { try { return !/^xext_/i.test(JSON.parse(store.getters.getToken || '{}').token || ''); } catch { return true; } },
    sourceBusy() { return this.sending || this.realtimeBusy || !!this.humanMedia; },
    sourceDevices() { return [...new Set(this.sourceOptions.devices.filter(row => !this.agentFilter || row.agent_id === this.agentFilter).map(row => row.mac_address))]; },
    authorizationKey() { return realtimeAuthorityKey(store.state); },
    speechBlocked() { return !!this.humanMedia || this.realtimeBusy || ['connecting','ending'].includes(this.realtimeState?.status); },
    realtimeMaterialBusy() { return this.realtimeState?.materialInput?.locked === true || this.realtimeState?.materialResponsePending === true; },
    realtimeBusy() { return !!this.realtimeState?.session && !REALTIME_TERMINAL.has(this.realtimeState.session.status); },
    composerTextOnly() { const pending = this.pending[this.selected?.id]; return this.realtimeBusy || !!(pending?.realtimeTextOnly && pending.text === this.draft); },
    realtimeAllowed() { return !!this.mediaUserId && this.selected?.state === 'AI_ACTIVE' && !this.humanMedia; },
    realtimeInjections() { return Object.fromEntries((this.realtimeState?.injections || []).map(item => [item.message_id, item])); },
    mediaUserId() { return String(store.state.userInfo?.id || store.state.userInfo?.user_id || store.state.userInfo?.userId || ''); },
    attachmentIdentity() { return attachmentIdentity(store.state) + (this.authorizationGeneration ? `:auth${this.authorizationGeneration}` : ''); },
    attachmentScope() { return `${this.attachmentIdentity}:${this.selected?.id || ''}`; },
    attachmentRows: { get() { return this.attachmentDrafts[this.selected?.id] || []; }, set(value) { if (this.selected) this.attachmentDrafts[this.selected.id] = value; } },
    attachmentsReady() { return attachmentRowsReady(this.attachmentRows); },
    humanActive() { return ['HUMAN_QUEUED', 'HUMAN_ACTIVE'].includes(this.selected?.state); },
    draft: {
      get() { return this.selected ? this.drafts[this.selected.id] || '' : ''; },
      set(value) { if (this.selected) this.drafts[this.selected.id] = value; },
    },
  },
  watch: { speechBlocked: { flush: 'sync', handler(value) { if (value) answerSpeechPlayback.stop(); } }, authorizationKey: { flush: 'sync', handler() { answerSpeechPlayback.stop(); this.$refs.aiRealtime?.stopLocalForIdentity(); this.authorizationGeneration++; } }, attachmentIdentity() { this.resetIdentity(); } },
  mounted() { this.applySourceRoute(); this.loadSpeechCapabilities(); this.loadSourceOptions(); this.loadConversations(); this.pollTimer = setInterval(() => this.pollConversation(), 4000); },
  beforeUnmount() { answerSpeechPlayback.stop(); this.speechCapabilityController?.abort(); this.disposed = true; this.indexController.abort(); this.sourceOptionController?.abort(); this.identityController.abort(); this.streamController?.abort(); this.selectionController?.abort(); this.selectionVersion++; clearInterval(this.pollTimer); },
  methods: {
    applySourceRoute() {
      if (!this.canBrowseSources) return;
      const query = this.$route?.query || {};
      if (!this.hardwareOnly && ['all','app','web','hardware'].includes(query.source)) this.sourceFilter = query.source;
      this.agentFilter = this.sourceFilter === 'hardware' && typeof query.agent_id === 'string' ? query.agent_id : '';
      this.deviceFilter = this.sourceFilter === 'hardware' && typeof query.mac_address === 'string' ? query.mac_address : '';
    },
    async loadSourceOptions() {
      this.sourceOptionController?.abort(); this.sourceOptionsError = false;
      if (!this.canBrowseSources) return;
      const controller = new AbortController(); this.sourceOptionController = controller;
      try { const options = await conversationApi.indexOptions(controller.signal); if (!controller.signal.aborted && !this.disposed) this.sourceOptions = { agents: options.agents || [], devices: options.devices || [] }; }
      catch { if (!controller.signal.aborted && !this.disposed) this.sourceOptionsError = true; }
    },
    indexSelected(item) { return item.kind === 'device_history' ? this.legacySelected?.row_key === item.row_key : !this.legacySelected && this.selected?.id === item.id; },
    clearSourceSelection() {
      answerSpeechPlayback.stop(); this.$refs.aiRealtime?.stopLocalForIdentity();
      this.selectionController?.abort(); this.streamController?.abort(); ++this.selectionVersion;
      this.legacyHistoryReady = false; this.selected = null; this.legacySelected = null; this.messages = []; this.supportRequest = null; this.runs = []; this.realtimeState = null; this.humanMedia = null; this.loadingMessages = false; this.pollBusy = false; this.error = '';
      this.resetMessageHistory();
    },
    sourceTabKeydown(event, source) {
      if (this.sourceBusy || !['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
      event.preventDefault(); const sources = ['all','app','web','hardware'], index = sources.indexOf(source);
      const next = event.key === 'Home' ? sources[0] : event.key === 'End' ? sources.at(-1) : sources[(index + (event.key === 'ArrowRight' ? 1 : -1) + sources.length) % sources.length];
      this.changeSource(next); this.$nextTick(() => document.getElementById(`source-tab-${next}`)?.focus());
    },
    changeSource(source) { if (this.hardwareOnly || this.sourceBusy || source === this.sourceFilter || !['all','app','web','hardware'].includes(source)) return; this.sourceFilter = source; this.agentFilter = ''; this.deviceFilter = ''; if (source === 'hardware') this.loadSourceOptions(); this.reloadSource(); },
    changeHardwareFilter(agent, device) { if (this.sourceBusy) return; this.agentFilter = agent; this.deviceFilter = device; this.reloadSource(); },
    reloadSource() { this.indexController.abort(); this.indexController = new AbortController(); ++this.indexVersion; this.loadingList = false; this.conversations = []; this.listCursor = 0; this.listHasMore = false; this.clearSourceSelection(); this.loadConversations(); },
    selectIndexItem(item) { if ((this.hardwareOnly && item.source !== 'hardware') || this.sourceBusy) return; this.$refs.workspaceLayout?.closeClients(); if (item.kind !== 'device_history') return this.selectConversation(item); this.clearSourceSelection(); this.legacySelected = item; },
    aiFailureKey,
    async loadSpeechCapabilities() {
      this.speechCapabilityController?.abort(); this.speechAvailable = false; this.skillsAvailable = false;
      if (this.hardwareOnly || !this.attachmentIdentity) return;
      const controller = new AbortController(); this.speechCapabilityController = controller;
      try { const data = await answerSpeechApi.capabilities(controller.signal); if (!controller.signal.aborted && !this.disposed) { this.speechAvailable = answerSpeechAvailable(data); this.skillsAvailable = readonlySkillsAvailable(data); } } catch { if (!controller.signal.aborted) this.speechAvailable = false; }
    },
    realtimeChanged(state) { if (this.legacySelected) return; const old = this.realtimeState; if (old?.responseId && !state.responseId && old.session?.id === state.session?.id) this.toolResultsRefresh++; this.realtimeState = state; },
    humanMediaChanged(session) { if (session && session.conversation_id !== this.selected?.id) return; this.humanMedia = session && !['ended','failed','rejected','canceled','expired'].includes(session.status) ? session : null; },
    openLiveFrame({ frame, conversationId }) { if (conversationId === this.selected?.id) this.$refs.annotationEditor?.openFrame(frame.id); },
    openAnnotation(source) { this.$refs.annotationEditor?.openSource(source); },
    openAnnotationSnapshot(id) { this.$refs.annotationEditor?.openSnapshot(id); },
    stageAnnotation({ scopeKey, row }) {
      if (scopeKey !== this.attachmentScope || this.sending || !this.selected || this.selected.state === 'RESOLVED') return;
      if (this.attachmentRows.some(item => item.attachment?.id === row.attachment.id)) return;
      if (this.attachmentRows.length >= 10) { this.error = this.$t('attachments.count'); return; }
      this.attachmentRows = [...this.attachmentRows,row];
    },
    senderKey,
    messageRole,
    messageIsOwn(message) { return isOwnMessage(message, this.mediaUserId); },
    resetIdentity() {
      this.resetMessageHistory();
      this.indexController.abort(); this.indexController = new AbortController(); ++this.indexVersion; this.sourceOptionController?.abort(); this.sourceOptions = { agents: [], devices: [] }; this.sourceOptionsError = false; this.legacySelected = null; this.sourceFilter = this.hardwareOnly ? 'hardware' : 'all'; this.agentFilter = ''; this.deviceFilter = '';
      answerSpeechPlayback.stop(); this.loadSpeechCapabilities();
      ++this.identityEpoch; ++this.selectionVersion; this.identityController.abort(); this.identityController = new AbortController();
      this.selectionController?.abort(); this.streamController?.abort();
      this.attachmentDrafts = {}; this.realtimeState = null; this.humanMedia = null; this.mediaNotices = {}; this.drafts = {}; this.pending = {}; this.conversations = []; this.selected = null; this.messages = []; this.supportRequest = null; this.runs = [];
      this.loadingList = false; this.loadingMessages = false; this.sending = false; this.supportBusy = false; this.creating = false; this.createVisible = false; this.error = ''; this.createError = ''; this.listCursor = 0; this.listHasMore = false;
    },
    runFor(message) { return this.runs.find(run => run.output_message_id === message.id); },
    async refreshRuns(id, version) {
      if (this.disposed || version !== this.selectionVersion) return;
      try {
        const page = await conversationApi.runs(id, this.selectionController?.signal);
        if (!this.disposed && version === this.selectionVersion) { this.runs = page.items.map(run => { const projection = this.streamProjections[run.output_message_id]; return projection ? { ...run, status: projection.status, attempt: projection.attempt, last_chunk_sequence: projection.chunkSequence } : run; }); this.aiAvailable = true; }
      } catch (error) {
        if (this.disposed || version !== this.selectionVersion || error.name === 'AbortError') return;
        if (error.status === 404) this.aiAvailable = false; else throw error;
      }
    },
    async runAction(run, action) {
      if (this.disposed || this.humanActive || this.selected?.state !== 'AI_ACTIVE' || !['cancel', 'retry'].includes(action)) return;
      const id = this.selected.id, version = this.selectionVersion, key = `${id}:${run.run_id}`;
      if (this.runBusy[key]) return;
      this.runBusy[key] = true;
      try {
        const updated = await conversationApi.runAction(id, run.run_id, action, this.selectionController?.signal);
        if (version !== this.selectionVersion || this.disposed) return;
        if (updated) this.runs = this.runs.map(item => item.run_id === updated.run_id ? updated : item);
        await this.pollConversation();
      } catch (error) { if (version === this.selectionVersion && !this.disposed && error.name !== 'AbortError') this.error = error.message; }
      finally { delete this.runBusy[key]; }
    },
    mergeSnapshot(incoming) {
      this.messages = mergeMessages(this.messages, incoming).map(message => {
        const projection = this.streamProjections[message.id];
        return projection ? { ...message, parts: projection.parts, status: projection.status } : message;
      });
    },
    applyStream(frame) {
      if (this.disposed || !/^\d+$/.test(frame.id || '') || BigInt(frame.id) <= BigInt(this.streamCursor)) return;
      let envelope; try { envelope = JSON.parse(frame.data); } catch { return; }
      if (!envelope || typeof envelope !== 'object') return;
      this.streamCursor = frame.id;
      const data = envelope.data || {}, id = data.message_id;
      if (['assistant.message.completed','ai.run.failed','ai.run.canceled'].includes(frame.type)) this.toolResultsRefresh++;
      if (frame.type === 'media.snapshot.saved' && this.selected) this.mediaNotices[this.selected.id] = true;
      if (!id) return;
      const previous = this.streamProjections[id];
      const next = projectAiEvent(previous, frame.type, data);
      if (!next || next === previous) return;
      this.streamProjections[id] = next;
      const existing = this.messages.find(message => message.id === id);
      this.mergeSnapshot([{ ...(existing || { id, sender_type: 'assistant', seq: data.seq, created_at: envelope.created_at }), seq: data.seq ?? existing?.seq, parts: next.parts, status: next.status }]);
      if (data.run_id) {
        const run = this.runs.find(item => item.run_id === data.run_id) || { run_id: data.run_id, output_message_id: id };
        this.runs = [...this.runs.filter(item => item.run_id !== data.run_id), { ...run, status: next.status, attempt: next.attempt, last_chunk_sequence: next.chunkSequence }];
      }
    },
    async startStream(id, version) {
      if (this.disposed || version !== this.selectionVersion) return;
      this.streamController?.abort();
      const controller = new AbortController(); this.streamController = controller;
      while (!controller.signal.aborted && !this.disposed && version === this.selectionVersion) {
        try {
          const response = await conversationApi.stream(id, this.streamCursor, controller.signal);
          if (controller.signal.aborted || this.disposed || version !== this.selectionVersion) { await response.body?.cancel().catch(() => {}); return; }
          if ([401, 403, 404].includes(response.status)) { if (response.status === 404) this.aiAvailable = false; await response.body?.cancel().catch(() => {}); return; }
          if (!response.ok || !response.body) { await response.body?.cancel().catch(() => {}); throw new Error('stream unavailable'); }
          await readSse(response.body, frame => { if (version === this.selectionVersion && !controller.signal.aborted) this.applyStream(frame); }, controller.signal);
        } catch (error) { if (controller.signal.aborted || this.disposed || version !== this.selectionVersion) return; }
        await abortableDelay(1500, controller.signal);
      }
    },
    async refreshSupport(id, version) {
      if (this.disposed || version !== this.selectionVersion) return;
      const detail = await conversationApi.detail(id, this.selectionController?.signal);
      if (version !== this.selectionVersion) return;
      this.selected = { ...this.selected, ...detail };
      let more = true;
      while (more) {
        const page = await conversationApi.events(id, this.eventCursor, this.selectionController?.signal);
        if (version !== this.selectionVersion) return;
        for (const event of page.items) {
          if (event.data?.request_id) this.supportRequest = { request_id: event.data.request_id };
          this.applyStream({ id: String(event.sequence), type: event.type, data: JSON.stringify(event) });
        }
        if (page.has_more && cursorInteger(page.next_cursor) <= cursorInteger(this.eventCursor)) throw new Error('历史记录游标异常，请刷新后重试');
        this.eventCursor = page.next_cursor; more = page.has_more;
      }
      if (this.supportRequest) {
        const request = await supportApi.get(this.supportRequest.request_id);
        if (version === this.selectionVersion) this.supportRequest = request;
      }
    },
    async pollConversation() {
      if (!this.selected || this.historyContextActive || this.pollBusy || this.loadingMessages || this.sending || this.supportBusy || this.disposed) return;
      if (this.selected.source === 'hardware') return this.pollHardwareMessages();
      this.pollBusy = true; const version = this.selectionVersion, id = this.selected.id;
      try {
        let more = true, after = '0'; const refreshed = [];
        while (more) {
          const page = await conversationApi.messages(id, after, this.selectionController?.signal);
          if (version !== this.selectionVersion) return;
          refreshed.push(...page.items);
          if (page.has_more && cursorInteger(page.next_cursor) <= cursorInteger(after)) throw new Error('历史记录游标异常，请刷新后重试');
          after = page.next_cursor; this.messageCursor = after; more = page.has_more;
        }
        this.mergeSnapshot(refreshed);
        await this.refreshSupport(id, version);
        await this.refreshRuns(id, version);
      } catch (error) { if (version === this.selectionVersion) this.error = error.message; }
      finally { if (version === this.selectionVersion) this.pollBusy = false; }
    },
    async supportAction(action) {
      if (!this.selected || this.supportBusy) return;
      const id = this.selected.id, version = this.selectionVersion; this.supportBusy = true; this.error = '';
      try {
        await action(id);
        if (version === this.selectionVersion) await this.refreshSupport(id, version);
      } catch (error) { if (version === this.selectionVersion) this.error = error.message; }
      finally { if (version === this.selectionVersion) this.supportBusy = false; }
    },
    handoff(serviceType = 'support_agent') {
      this.$refs.aiRealtime?.shutdown();
      return this.supportAction(async id => {
        const key = `${id}:${serviceType}`;
        const clientId = this.handoffPending[key] || (this.handoffPending[key] = crypto.randomUUID());
        const result = await conversationApi.handoff(id, { client_request_id: clientId, requested_mode: 'text', target_agent_user_id: null, service_type: serviceType });
        delete this.handoffPending[key];
        if (this.selected?.id === id) this.supportRequest = result;
      });
    },
    cancelHandoff() { const id = this.supportRequest?.request_id; return id && this.supportAction(() => supportApi.action(id, 'cancel')); },
    resumeAi() { return this.supportAction(id => conversationApi.resume(id)); },
    formatDate(value, time = false, hardware = false) {
      if (!value) return '';
      const options = { month: '2-digit', day: '2-digit', ...(time ? { hour: '2-digit', minute: '2-digit' } : {}) };
      if (hardware) return formatBeijingTime(value, 'zh-CN', { ...options, ...(time ? { second: '2-digit' } : {}) });
      return new Date(value).toLocaleString('zh-CN', options);
    },
    openCreate() { if (this.hardwareOnly) return; this.newTitle = ''; this.createError = ''; this.createVisible = true; },
    focusTitle() { this.$refs.titleInput?.focus(); },
    async loadConversations(more = false) {
      if (this.loadingList) return;
      this.loadingList = true; const epoch = this.identityEpoch, indexVersion = this.indexVersion;
      try {
        const page = this.canBrowseSources
          ? await conversationApi.index({ before: more ? this.listCursor : '', limit: 50, source: this.hardwareOnly ? 'hardware' : this.sourceFilter, agent_id: this.agentFilter, mac_address: this.deviceFilter }, this.indexController.signal)
          : await conversationApi.list(more ? this.listCursor : 0, this.identityController.signal);
        if (this.disposed || epoch !== this.identityEpoch || indexVersion !== this.indexVersion) return;
        const items = this.hardwareOnly ? page.items.filter(item => item.source === 'hardware') : page.items;
        this.conversations = more ? [...this.conversations, ...items] : items;
        this.listCursor = page.next_cursor; this.listHasMore = page.has_more;
        if (!this.selected && !this.legacySelected && this.conversations.length) await this.selectIndexItem(this.conversations[0]);
      } catch (error) { if (!this.disposed && epoch === this.identityEpoch && indexVersion === this.indexVersion && error.name !== 'AbortError') this.error = error.message; }
      finally { if (!this.disposed && epoch === this.identityEpoch && indexVersion === this.indexVersion) this.loadingList = false; }
    },
    async createConversation() { if (this.hardwareOnly) return;
      if (!this.newTitle.trim() || this.creating) return;
      this.creating = true; this.createError = ''; const epoch = this.identityEpoch;
      try {
        const item = await conversationApi.create(this.newTitle.trim(), this.identityController.signal);
        if (this.disposed || epoch !== this.identityEpoch) return;
        this.createVisible = false;
        await this.loadConversations();
        if (this.disposed || epoch !== this.identityEpoch) return;
        await this.selectConversation(item);
      } catch (error) { if (!this.disposed && epoch === this.identityEpoch && error.name !== 'AbortError') this.createError = error.message; }
      finally { if (!this.disposed && epoch === this.identityEpoch) this.creating = false; }
    },
    async selectConversation(item) {
      this.legacySelected = null;
      answerSpeechPlayback.stop();
      this.streamController?.abort(); this.selectionController?.abort();
      this.selectionController = new AbortController();
      const version = ++this.selectionVersion;
      this.runs = []; this.aiAvailable = null; this.streamCursor = '0'; this.streamProjections = {}; this.pollBusy = false; this.sending = false; this.supportBusy = false;
      this.humanMedia = null; this.selected = item; this.messages = []; this.supportRequest = null; this.messageCursor = '0'; this.eventCursor = '0'; this.loadingMessages = true; this.error = '';
      this.resetMessageHistory();
      try {
        if (item.source === 'hardware') {
          const detail = await conversationApi.detail(item.id, this.selectionController.signal);
          if (this.disposed || version !== this.selectionVersion) return;
          this.selected = { ...item, ...detail };
          // The immutable hardware ledger allocates contiguous sequences starting at 1.
          // Its last_seq lets the existing forward API read just the latest ten rows.
          const last = cursorInteger(detail.last_seq), after = last > 10n ? last - 10n : 0n;
          const page = await conversationApi.messages(item.id, String(after), this.selectionController.signal, 10);
          if (this.disposed || version !== this.selectionVersion) return;
          this.mergeSnapshot(page.items);
          this.messageBefore = String(after + 1n); this.messageHasMore = after > 0n;
          this.messageCursor = String(last);
          this.scrollToLatest();
          return;
        }
        let after = 0; let more = true;
        while (more) {
          const page = await conversationApi.messages(item.id, after, this.selectionController.signal);
          if (version !== this.selectionVersion) return;
          this.mergeSnapshot(page.items);
          if (page.has_more && cursorInteger(page.next_cursor) <= cursorInteger(after)) throw new Error('历史记录游标异常，请刷新后重试');
          after = page.next_cursor; this.messageCursor = after; more = page.has_more;
        }
        await this.refreshSupport(item.id, version);
        await this.refreshRuns(item.id, version);
        if (this.aiAvailable && version === this.selectionVersion) this.startStream(item.id, version);
        this.scrollToLatest();
      } catch (error) { if (version === this.selectionVersion) this.error = error.message; }
      finally { if (version === this.selectionVersion) this.loadingMessages = false; }
    },
    async deviceInputSubmitted(result) {
      if (this.disposed || !this.hardwareSelection || !result.conversationId) return;
      const target = this.hardwareSelection;
      const item = this.selected?.id === result.conversationId ? this.selected : { ...target, id: result.conversationId, kind: 'conversation', source: 'hardware', row_key: 'conversation:' + result.conversationId, title: 'ESP32 AI' };
      await this.selectConversation(item);
      if (!this.disposed) void this.loadConversations();
    },
    openHistorySearch() {
      if (!this.canSearchHardwareHistory) return;
      this.$refs.workspaceLayout?.closeTools();
      if (this.legacySelected) this.$refs.legacyHistory?.openHistorySearch();
      else this.historyVisible = true;
    },
    searchHistoryPage(before, signal) {
      if (this.selected?.source !== 'hardware') throw new Error('HISTORY_SCOPE_UNAVAILABLE');
      return readConversationHistoryPage(conversationApi, this.selected.id, before, signal);
    },
    async locateHistoryMessage(message) {
      if (this.selected?.source !== 'hardware' || this.loadingMessages || this.disposed) return;
      const id = this.selected.id, seq = cursorInteger(message.seq);
      // A context jump replaces the visible window, so fence pending poll/history reads too.
      this.selectionController?.abort(); this.selectionController = new AbortController();
      const version = ++this.selectionVersion;
      this.pollBusy = false; this.loadingOlderMessages = false;
      const after = seq > 5n ? seq - 5n : 0n;
      this.loadingMessages = true; this.error = '';
      try {
        const page = await conversationApi.messages(id, String(after), this.selectionController.signal, 10);
        if (this.disposed || version !== this.selectionVersion) return;
        if (!page.items.some(row => row.id === message.id)) throw new Error(this.$t('historyFinder.jumpFailed'));
        this.messages = mergeMessages([], page.items);
        this.messageBefore = String(after + 1n); this.messageHasMore = after > 0n; this.historyError = false;
        this.historyContextActive = true; this.highlightedMessageId = message.id; this.historyVisible = false;
        await this.$nextTick();
        if (this.disposed || version !== this.selectionVersion) return;
        const list = this.$refs.messageList, target = [...(list?.querySelectorAll('[data-message-id]') || [])].find(row => row.dataset.messageId === message.id);
        if (list && target) {
          list.scrollTop += target.getBoundingClientRect().top - list.getBoundingClientRect().top - (list.clientHeight - target.clientHeight) / 2;
          this.lastMessageScrollTop = list.scrollTop; target.focus({ preventScroll: true });
        }
      } catch (error) { if (!this.disposed && version === this.selectionVersion && error.name !== 'AbortError') this.error = error.message; }
      finally { if (version === this.selectionVersion) this.loadingMessages = false; }
    },
    resetMessageHistory() { this.historyVisible = false; this.historyContextActive = false; this.highlightedMessageId = ''; this.messageBefore = '1'; this.messageHasMore = false; this.loadingOlderMessages = false; this.historyError = false; this.lastMessageScrollTop = 0; },
    messageListScrolled() {
      const top = this.$refs.messageList?.scrollTop || 0, movingUp = top < this.lastMessageScrollTop;
      this.lastMessageScrollTop = top;
      if (movingUp && top <= 64 && !this.historyError) this.loadOlderMessages();
    },
    async loadOlderMessages() {
      if (this.selected?.source !== 'hardware' || !this.messageHasMore || this.loadingMessages || this.loadingOlderMessages || this.disposed) return;
      const id = this.selected.id, version = this.selectionVersion, before = cursorInteger(this.messageBefore);
      const after = before > 11n ? before - 11n : 0n;
      this.loadingOlderMessages = true; this.historyError = false;
      try {
        const page = await conversationApi.messages(id, String(after), this.selectionController.signal, 10);
        if (this.disposed || version !== this.selectionVersion) return;
        const list = this.$refs.messageList, height = list?.scrollHeight || 0, top = list?.scrollTop || 0;
        this.mergeSnapshot(page.items.filter(message => cursorInteger(message.seq) < before));
        this.messageBefore = String(after + 1n); this.messageHasMore = after > 0n;
        this.loadingOlderMessages = false;
        await this.$nextTick();
        if (!this.disposed && version === this.selectionVersion && list) { list.scrollTop = top + list.scrollHeight - height; this.lastMessageScrollTop = list.scrollTop; }
      } catch (error) { if (!this.disposed && version === this.selectionVersion && error.name !== 'AbortError') this.historyError = true; }
      finally { if (version === this.selectionVersion) this.loadingOlderMessages = false; }
    },
    async pollHardwareMessages() {
      this.pollBusy = true;
      const id = this.selected.id, version = this.selectionVersion;
      // Refresh the recent rows (including status changes), plus any newly appended rows.
      const cursor = cursorInteger(this.messageCursor);
      let after = String(cursor > 10n ? cursor - 10n : 0n), more = true;
      try {
        while (more) {
          const page = await conversationApi.messages(id, after, this.selectionController.signal);
          if (this.disposed || version !== this.selectionVersion) return;
          const list = this.$refs.messageList, atBottom = list && list.scrollHeight - list.scrollTop - list.clientHeight <= 64;
          if (page.has_more && cursorInteger(page.next_cursor) <= cursorInteger(after)) throw new Error('历史记录游标异常，请刷新后重试');
          this.mergeSnapshot(page.items); after = page.next_cursor; more = page.has_more;
          if (cursorInteger(after) > cursorInteger(this.messageCursor)) this.messageCursor = String(after);
          if (atBottom) this.scrollToLatest();
        }
      } catch (error) { if (!this.disposed && version === this.selectionVersion && error.name !== 'AbortError') this.error = error.message; }
      finally { if (version === this.selectionVersion) this.pollBusy = false; }
    },
    scrollToLatest() { const version = this.selectionVersion; this.$nextTick(() => { const list = this.$refs.messageList; if (!this.disposed && version === this.selectionVersion && list) { list.scrollTop = list.scrollHeight; this.lastMessageScrollTop = list.scrollTop; } }); },
    composerKeydown(event) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); this.sendMessage(); } },
    async sendMessage() {
      if (this.hardwareOnly || !this.selected || this.selected.source === 'hardware' || this.realtimeMaterialBusy || this.selected.state === 'RESOLVED' || (!this.draft.trim() && (this.composerTextOnly || !this.attachmentRows.length)) || (!this.composerTextOnly && !this.attachmentsReady) || this.sending || this.loadingMessages) return;
      const id = this.selected.id, version = this.selectionVersion; const text = this.draft, rows = this.composerTextOnly ? [] : [...this.attachmentRows], parts = mixedMessageParts(text, rows), signature = JSON.stringify(parts);
      const previous = this.pending[id];
      const clientId = previous?.signature === signature ? previous.clientId : crypto.randomUUID();
      this.pending[id] = { id, text, signature, clientId, realtimeTextOnly: this.composerTextOnly }; this.sending = true; this.error = '';
      try {
        const message = await conversationApi.send(id, { client_message_id: clientId, parts }, this.selectionController?.signal);
        if (version !== this.selectionVersion || this.disposed) return;
        this.mergeSnapshot([message]);
        if (this.draft === text) this.draft = '';
        this.attachmentRows = this.attachmentRows.filter(row => !rows.some(sent => sent.clientUploadId === row.clientUploadId));
        delete this.pending[id]; this.scrollToLatest();
      } catch (error) { if (version === this.selectionVersion && !this.disposed && error.name !== 'AbortError') this.error = `${error.message}。未确认的内容已保留，重试不会重复保存。`; }
      finally { if (version === this.selectionVersion) this.sending = false; }
    },
  },
};
</script>

<style scoped lang="scss">
@use '../styles/chat-messages' as messages;
.device-context{padding-bottom:18px;margin-bottom:18px;border-bottom:1px solid var(--admin-border)}.device-context h3{font-size:var(--admin-font-md);margin:0 0 12px}.device-context>strong{font-size:var(--admin-font-base);overflow-wrap:anywhere}.device-context p{color:var(--el-text-color-secondary);font-size:var(--admin-font-xs);line-height:1.7}.device-context dl{margin:14px 0 0;display:grid;gap:12px}.device-context dl>div{display:flex;justify-content:space-between;gap:10px;font-size:var(--admin-font-xs)}.device-context dt{flex:none;color:var(--el-text-color-secondary)}.device-context dd{margin:0;text-align:right;overflow-wrap:anywhere}

.panel-heading-actions{display:flex;align-items:center;justify-content:flex-end;gap:12px;flex-shrink:0}.history-search-entry{display:flex;align-items:center;gap:6px;border:0;background:transparent;color:var(--el-color-primary);font-size:var(--admin-font-xs);padding:8px 0;white-space:nowrap}.history-context-bar{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:10px 24px;background:var(--el-color-primary-light-9);color:var(--el-text-color-regular);font-size:var(--admin-font-xs);flex-shrink:0}.history-context-bar button{border:0;background:transparent;color:var(--el-color-primary);font-size:var(--admin-font-xs);cursor:pointer}.message-row.history-target .message-bubble{box-shadow:0 0 0 2px var(--el-color-primary-light-5);background:var(--el-color-primary-light-9)}.message-row.history-target:focus{outline:none}
@media(max-width:480px){.panel-heading-actions{gap:5px}.history-search-entry{font-size:var(--admin-font-xs)}.panel-heading>div:first-child{min-width:0}}
.conversation-source-tabs{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:2px;padding:3px;background:var(--el-fill-color);border-radius:6px;margin-bottom:12px}.conversation-source-tabs button{min-width:0;min-height:34px;padding:7px 3px;border:0;border-radius:4px;background:transparent;color:var(--el-text-color-regular);font-size:var(--admin-font-xs);white-space:nowrap}.conversation-source-tabs button[aria-selected="true"]{background:var(--el-bg-color);color:var(--el-color-primary);font-weight:600;box-shadow:0 1px 4px #25324718}.conversation-source-tabs button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:0}.conversation-source-tabs button:hover:not(:disabled){color:var(--el-color-primary)}
.conversation-source-filters{flex-shrink:0;padding:0 8px 16px}.conversation-source-filters label{display:block;font-size:var(--admin-font-xs);color:var(--el-text-color-secondary);margin:0 0 6px}.conversation-source-filters label:not(:first-child){margin-top:10px}.conversation-source-filters select{width:100%;height:34px;border:1px solid var(--admin-border);border-radius:5px;padding:0 8px;background:var(--el-bg-color);color:var(--admin-text);font:inherit;font-size:var(--admin-font-base)}.source-options-error{font-size:var(--admin-font-xs);color:var(--el-color-danger);line-height:1.6}.hardware-history-panel{min-height:0!important;overflow:hidden}.hardware-readonly-note{padding:18px 24px;border-top:1px solid var(--admin-border);color:var(--el-text-color-secondary);font-size:var(--admin-font-base);background:var(--el-bg-color);flex-shrink:0}
@media(max-width:760px){.workspace-grid.with-source-filters{grid-template-rows:230px minmax(380px,1fr)}.workspace-grid.with-hardware-filters{grid-template-rows:340px minmax(380px,1fr)}.conversation-source-filters{padding:0 0 10px}.with-source-filters .new-conversation{position:static;margin-bottom:5px}.with-source-filters .conversation-list{min-height:65px}.hardware-history-panel{min-height:380px!important}}

.support-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: var(--admin-font-xs); padding: 8px 0; }
.message-row.incoming { justify-content: flex-end; flex-direction: row-reverse; }
.message-row.incoming .message-meta { text-align: left; }
.message-row.incoming .message-bubble { background: #f2f5f7; border-radius: 2px 12px 12px; }
.message-row.incoming .message-saved { text-align: left; }
.conversation-workspace { height: 100%; min-height: 0; color: #23334b; background: #f5f7fb; display: flex; flex-direction: column; text-align: left; font-family: var(--admin-font-family); border: 1px solid #e5e9f0; border-radius: 6px; overflow: hidden; }
.workspace-header { height: 68px; flex: 0 0 68px; background: #fff; border-bottom: 1px solid #e7ebf2; display: flex; align-items: center; justify-content: space-between; padding: 0 30px; gap: 12px; }
.workspace-brand { display: flex; align-items: center; gap: 12px; font-size: var(--admin-font-lg); }
.brand-icon { width: 34px; height: 34px; border-radius: 10px; background: #477bfa; color: white; display: grid; place-items: center; font-size: var(--admin-font-title); }
.environment-label { padding: 4px 9px; border-radius: 5px; color: #60759d; background: #f0f4fb; font-size: var(--admin-font-xs); font-weight: 500; }
.back-link { color: #67758b; text-decoration: none; font-size: var(--admin-font-base); white-space: nowrap; }
.workspace-grid { display: grid; grid-template-columns: 250px minmax(0, 1fr); flex: 1; min-height: 0; width: 100%; margin: 0 auto; }
.conversation-sidebar { display: flex; flex-direction: column; min-height: 0; padding: 26px 18px 16px; border-right: 1px solid #e6ebf3; }
.sidebar-heading { display: flex; justify-content: space-between; align-items: center; font-size: var(--admin-font-base); font-weight: 600; padding: 0 10px 15px; color: #67758b; }
button { font: inherit; cursor: pointer; transition: background .15s; }
button:disabled { cursor: not-allowed; opacity: .5; }
button:focus-visible, a:focus-visible, textarea:focus-visible { outline: 3px solid #91b2ff; outline-offset: 3px; }
.icon-button { width: 30px; height: 30px; border: 0; color: #67758b; border-radius: 6px; background: transparent; font-size: var(--admin-font-lg); }
.icon-button:hover { background: #e8edf6; }
.new-conversation { flex-shrink: 0; border: 1px solid #dce5f6; border-radius: 9px; color: #3d70e8; background: #fff; padding: 12px; font-size: var(--admin-font-base); text-align: left; }
.new-conversation i { margin: 0 10px 0 5px; }
.conversation-list { overflow-y: auto; padding-top: 20px; flex: 1; }
.conversation-item { display: flex; align-items: flex-start; gap: 11px; width: 100%; border: 0; border-radius: 9px; background: transparent; color: #66748a; text-align: left; padding: 14px 12px; margin-bottom: 5px; }
.conversation-item.selected { background: #e8effe; color: #346bdc; }
.conversation-item:hover { background: #eaf0fa; }
.conversation-item > i { font-size: var(--admin-font-lg); margin-top: 1px; }
.conversation-item > span { min-width: 0; }
.conversation-item strong { display: block; font-weight: 500; font-size: var(--admin-font-base); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.conversation-item small { display: block; margin-top: 7px; color: #8b97a9; font-size: var(--admin-font-xs); }
.sidebar-empty { color: #8b97a9; font-size: var(--admin-font-base); line-height: 1.9; padding: 26px 10px; }
.sidebar-note { color: #8b97a9; font-size: var(--admin-font-xs); display: flex; align-items: center; gap: 7px; padding: 15px 10px 0; }
.connection-dot { width: 6px; height: 6px; border-radius: 50%; background: #62ad91; }
.conversation-panel { display: flex; flex-direction: column; min-width: 0; min-height: 0; background: #fff; }
.panel-heading { padding: 25px 34px 23px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #f0f2f6; gap: 15px; }
h1 { font-size:var(--admin-font-title); line-height: 1.5; margin: 0; font-weight: 600; overflow-wrap: anywhere; }
.panel-heading p { margin: 6px 0 0; color: #8a96a8; font-size: var(--admin-font-xs); }
.message-list { flex: 1; min-height: 0; overflow-y: auto; padding: 24px 40px; }
.hardware-messages { overflow-anchor: none; }
.message-history-status { display: flex; align-items: center; justify-content: center; min-height: 42px; margin-bottom: 16px; color: #8b97a9; font-size: var(--admin-font-xs); }
.welcome-state { min-height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; box-sizing: border-box; padding: 25px; }
.welcome-icon { display: grid; place-items: center; width: 68px; height: 68px; color: #7594d4; background: #f0f5ff; border-radius: 20px; font-size: var(--admin-font-illustration); margin-bottom: 8px; }
.welcome-state h2 { font-size: var(--admin-font-title); font-weight: 500; margin: 18px 0 9px; }
.welcome-state p { font-size: var(--admin-font-base); line-height: 1.9; color: #8b97a9; margin: 0 0 24px; }
.primary-button { padding: 11px 18px; border: 0; border-radius: 8px; background: #477bfa; color: #fff; font-size: var(--admin-font-base); }
.primary-button:hover:not(:disabled) { background: #356be7; }
.primary-button i { margin: 0 4px; }
.message-row { display: flex; justify-content: flex-end; gap: 12px; margin: 0 auto 26px; max-width: 940px; }
.message-content { max-width: 78%; min-width: 0; }
.message-meta { text-align: right; color: #6e7a8c; font-size: var(--admin-font-xs); margin-bottom: 9px; }
.message-meta span { color: #9aa4b3; font-size: var(--admin-font-xs); margin-left: 9px; }
.message-bubble { background: #edf3ff; border-radius: 12px 2px 12px 12px; padding: 13px 18px; font-size: var(--admin-font-base); line-height: 1.8; color: #344968; }
.message-bubble p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.message-saved { color: #9aa4b3; font-size: var(--admin-font-xs); text-align: right; margin-top: 7px; }
.sender-avatar { margin-top: 23px; }
.location-card small { display: block; margin-top: 5px; color: #7b8ca5; }
.composer-area { padding: 0 34px 24px; }
.capability-note { display: flex; gap: 7px; align-items: baseline; color: #8a96a8; font-size: var(--admin-font-xs); line-height: 1.7; padding: 10px 2px; }
.message-composer { border: 1px solid #dde5f2; border-radius: 12px; box-shadow: 0 4px 16px #293b5c06; padding: 14px 16px 12px; }
.message-composer:focus-within { border-color: #83a5f8; }
textarea { display: block; border: 0; background: transparent; width: 100%; resize: none; font: inherit; font-size: var(--admin-font-base); line-height: 1.7; color: #344968; padding: 0; box-sizing: border-box; }
textarea:focus { outline: none; }
textarea::placeholder { color: #a1aabc; }
.composer-bottom { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 8px; }
.composer-bottom > span { font-size: var(--admin-font-xs); color: #97a3b5; }
.workspace-error { background: #fff6ee; color: #a36624; margin: 14px 34px 0; padding: 10px 12px; border-radius: 7px; display: flex; gap: 8px; font-size: var(--admin-font-xs); line-height: 1.6; }
.workspace-error span { flex: 1; }
.workspace-error button { background: transparent; border: 0; color: inherit; }
.text-button { border: 0; background: transparent; color: #477bfa; font-size: var(--admin-font-xs); padding: 12px; }
.title-label { display: block; color: #67758b; font-size: var(--admin-font-base); margin-bottom: 10px; }
.dialog-hint { color: #97a3b5; font-size: var(--admin-font-xs); }
.create-error { color: #c04e40; font-size: var(--admin-font-xs); }
@media (max-width: 760px) {
  .workspace-header { padding: 0 16px; height: 58px; flex-basis: 58px; }
  .workspace-brand { gap: 8px; font-size: var(--admin-font-md); }
  .brand-icon { width: 28px; height: 28px; font-size: var(--admin-font-title); }
  .workspace-grid { grid-template-columns: minmax(0, 1fr); grid-template-rows: 160px minmax(min-content, 1fr); overflow-y: auto; overflow-x: hidden; }
  .conversation-panel { min-height: min-content; }
  .conversation-sidebar { position: relative; border-right: 0; border-bottom: 1px solid #e6ebf3; padding: 10px 16px; }
  .sidebar-heading { padding: 0 0 5px; }
  .new-conversation { position: absolute; right: 16px; top: 9px; padding: 6px 12px; font-size: var(--admin-font-xs); }
  .conversation-list { display: flex; gap: 8px; overflow-x: auto; overflow-y: hidden; padding-top: 4px; }
  .conversation-item { width: 190px; min-width: 190px; padding: 9px 10px; }
  .sidebar-note { padding: 5px 0 0; }
  .sidebar-empty { padding: 0; margin: 0; }
  .panel-heading { padding: 13px 18px; }
  h1 { font-size:var(--admin-font-title); }
  .panel-heading p { font-size: var(--admin-font-xs); }
  .message-list { flex: 1 0 200px; min-height: 200px; box-sizing: border-box; padding: 18px 15px; }
  .message-content { max-width: 85%; }
  .composer-area { flex-shrink: 0; padding: 0 14px 14px; }
  .keyboard-hint { display: none; }
  .workspace-error { margin: 10px 14px 0; }
  .welcome-state { padding: 10px; }
  .welcome-state h2 { font-size: var(--admin-font-md); margin-top: 12px; }
  .welcome-state p { font-size: var(--admin-font-xs); margin-bottom: 12px; }
  .welcome-icon { width: 45px; height: 45px; font-size: var(--admin-font-xl); border-radius: 14px; }
}
@include messages.thread;
</style>
