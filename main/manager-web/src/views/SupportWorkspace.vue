<template>
  <section class="support-workspace">
    <el-alert v-if="error" :title="error" type="error" show-icon @close="error = ''" />
    <p v-if="loading" role="status">{{ $t('support.loading') }}</p>
    <p v-if="!loading && !profile" role="status">{{ admin ? `${$t('support.agent')} · ${$t('knowledgeBaseDialog.statusDisabled')}` : $t('support.denied') }}</p>
    <template v-if="profile">
      <header class="workspace-heading">
        <div><span class="eyebrow">{{ $t('expertWorkspace.brand') }}</span><h1>{{ $t(serviceType === 'expert' ? 'conversationManagement.experts' : 'conversationManagement.support') }}</h1></div>
        <div class="presence">
          <div class="desk-counts"><span class="desk-count waiting"><LegacyIcon class="el-icon-clock" />{{ $t('conversationManagement.pending') }} <b>{{ queue.length }}</b></span><span class="desk-count assigned"><LegacyIcon class="el-icon-chat-line-round" />{{ $t('conversationManagement.assigned') }} <b>{{ assigned.filter(item => item.status === 'active').length }}</b></span></div>
          <strong>{{ agentName }}</strong>
          <div v-if="roleAllowed" class="reception-control" :class="accepting ? 'is-accepting' : 'is-paused'" :aria-busy="presenceBusy || sessionPresenceBusy">
            <span class="reception-label"><LegacyIcon :class="accepting ? 'el-icon-headset' : 'el-icon-video-pause'" />{{ $t(accepting ? 'support.accepting' : 'support.paused') }}</span>
            <el-switch size="small" :aria-label="$t('support.accepting')" v-model="accepting" :disabled="presenceBusy || sessionPresenceBusy" @change="updatePresence" />
          </div>
        </div>
      </header>
      <ExpertWorkspaceLayout ref="expertLayout" collaboration :media-state="mediaWorkspaceState" :annotation-visible="annotationVisible">
        <template #contacts><ExpertContactList :assigned="assigned" :queue="queue" :titles="titles" :selected-id="selected?.request_id || ''" :accepting="accepting && roleAllowed" :busy="sending || historyLoading || actionBusy" :refreshing="refreshBusy" :unread-counts="unreadCounts" :new-requests="newRequestBadges" :activity="contactActivity" @select="selectContact" @claim="claim" @refresh="refresh" /></template>
        <template #header><header class="conversation-heading">
          <div><h2>{{ selected ? (titles[selected.conversation_id] || $t('support.consultation')) : $t('expertWorkspace.noSelection') }}</h2><p v-if="selected"><span class="status-dot" />{{ $t('support.' + selected.status) }}<span class="request-id" :title="selected.request_id"> · {{ selected.request_id }}</span></p><p v-else>{{ $t('expertWorkspace.selectHint') }}</p></div>
          <div v-if="selected" class="session-actions">
            <span v-if="mediaCallNoticeKey" class="call-result" role="status"><LegacyIcon class="el-icon-info" />{{ $t(mediaCallNoticeKey) }}</span>
            <template v-if="active"><span class="call-availability" :class="'is-' + callStatusKey.split('_').pop()" role="status"><LegacyIcon :class="callStatusKey.endsWith('_online') ? 'el-icon-circle-check' : callStatusKey.endsWith('_busy') ? 'el-icon-clock' : 'el-icon-circle-close'" />{{ $t(callStatusKey) }}</span>
              <el-button type="primary" plain :disabled="!canCall('audio')" @click="$refs.mediaOverlay?.openOutgoing('audio')"><LegacyIcon class="el-icon-phone" />{{ $t('media.audioCall') }}</el-button>
              <el-button type="success" plain :disabled="!canCall('video')" @click="$refs.mediaOverlay?.openOutgoing('video')"><LegacyIcon class="el-icon-video-camera" />{{ $t('media.videoCall') }}</el-button>
            </template>
            <el-button type="warning" plain :disabled="actionBusy || sending || !active" @click="openTransfer"><LegacyIcon class="el-icon-switch" />{{ $t('conversationManagement.transfer') }}</el-button>
            <el-button type="danger" :disabled="actionBusy || sending || !active" @click="end('resolve')"><LegacyIcon class="el-icon-circle-check" />{{ $t('support.resolve') }}</el-button>
            <el-button type="info" plain :disabled="actionBusy || sending || !active" @click="end('requeue')"><LegacyIcon class="el-icon-refresh-right" />{{ $t('support.requeue') }}</el-button>
          </div>
        </header></template>
        <template #media><section class="media-region" :aria-label="$t('expertWorkspace.liveVideo')">
          <MediaSessionOverlay ref="mediaOverlay" @call-availability="callAvailability = $event" :conversation-id="selected?.conversation_id || ''" :request-id="active ? selected.request_id : ''" :service-type="serviceType" :incoming-session-id="String($route?.query?.media_session || '')" :identity="attachmentIdentity" :user-id="mediaUserId" staff :writable="active" embedded adaptive :capture-disabled="annotationVisible" @workspace-state="mediaWorkspaceState = $event" @changed="mediaChanged" @frame="openLiveFrame" />
        </section></template>
        <template #annotation><section v-show="annotationVisible" class="annotation-region" data-workspace-region="annotation" :aria-label="$t('expertWorkspace.frozenFrame')">
          <p class="frozen-frame-caption">{{ $t('expertWorkspace.frozenFrame') }}</p>
          <AnnotationEditor ref="annotationEditor" :conversation-id="selected?.conversation_id || ''" :scope-key="attachmentScope" :identity="attachmentIdentity" :writable="active" :disabled="actionBusy || historyLoading" :reply-disabled="sending || actionBusy || historyLoading" embedded docked @visibility="annotationVisible = $event" @ready="stageAnnotation" />
        </section></template>
        <section class="chat" :aria-label="$t('expertWorkspace.chat')">
          <div class="chat-heading"><h3>{{ $t('expertWorkspace.chat') }}</h3><small>{{ selected ? messages.length : '—' }}</small><button v-if="selected" type="button" class="history-search-entry" :disabled="!canSearchHistory" @click="openHistorySearch"><LegacyIcon class="el-icon-search" />{{ $t('historyFinder.title') }}</button></div>
          <div v-if="highlightedHistoryMessageId" class="history-context-bar"><span>{{ $t('historyFinder.historical') }}</span><button type="button" @click="showLatestHistory">{{ $t('historyFinder.latest') }}</button></div>
          <template v-if="selected">
            <p v-if="historyLoading" role="status">{{ $t('support.loading') }}</p>
            <div ref="messageHistory" class="history" aria-live="polite" :aria-busy="historyLoading" @scroll.passive="updateDeskView"><p v-if="!messages.length && !historyLoading" class="chat-empty">{{ $t('expertWorkspace.chatEmpty') }}</p><article v-for="message in messages" :key="message.id" :id="`support-message-${message.id}`" :data-message-id="message.id" tabindex="-1" class="chat-message" :class="['sender-' + messageRole(message.sender_type), { 'staff-message': ['support_agent', 'expert'].includes(message.sender_type), 'own-message': messageIsOwn(message), 'history-target': highlightedHistoryMessageId === message.id }]"><MessageAvatar class="thread-avatar" :sender-type="message.sender_type" :own="messageIsOwn(message)" :label="$t('support.' + senderKey(message.sender_type))" /><div class="thread-body"><small class="thread-meta">{{ $t('support.' + senderKey(message.sender_type)) }} <span v-if="messageIsOwn(message)" class="message-self-label">{{ $t('chatMessage.me') }}</span> · {{ formatDate(message.created_at) }}</small><div class="message-content thread-bubble"><template v-for="(part, index) in message.parts" :key="index"><p v-if="part.type === 'text'">{{ part.text }}</p><AttachmentPart v-else-if="['image', 'video', 'audio', 'file'].includes(part.type) && part.attachment_id" :part="part" :scope-key="attachmentScope" :can-annotate="active && !sending && !historyLoading && !actionBusy" @annotate="openAnnotation" @snapshot="openAnnotationSnapshot" /><p v-else-if="part.type === 'location'">{{ part.latitude }}, {{ part.longitude }} · {{ part.coordinate_system }}</p><p v-else>{{ $t('support.nonText') }}</p></template></div></div></article></div>
          </template><p v-else class="chat-empty">{{ $t('expertWorkspace.selectHint') }}</p>
        </section>
        <template #reply>
          <div v-if="selected" class="composer">
            <AttachmentComposer ref="replyAttachments" :key="attachmentScope" v-model="attachmentRows" :conversation-id="selected.conversation_id" :identity="attachmentIdentity" :disabled="sending || actionBusy || !active || historyLoading" />
            <form @paste="$refs.replyAttachments?.pasteImages($event)" @submit.prevent="send">
              <el-input v-model="draft" type="textarea" :rows="2" maxlength="8000" :disabled="sending || actionBusy || !active" :aria-label="$t('support.reply')" :placeholder="active ? $t('expertWorkspace.pasteImageHint') : ''" @keydown="composerKeydown" />
              <div class="composer-footer"><small>{{ $t(active ? 'expertWorkspace.sendHint' : 'expertWorkspace.readonly') }}</small><el-button native-type="submit" type="primary" :loading="sending" :disabled="actionBusy || !active || (!draft.trim() && !attachmentRows.length) || !attachmentsReady || historyLoading"><LegacyIcon class="el-icon-promotion" />{{ $t('support.reply') }}</el-button></div>
              <p class="retry-note">{{ $t('support.retryNote') }}</p>
            </form>
          </div>
        </template>
        <template #tools>
          <section class="tool-section context"><h3>{{ $t('expertWorkspace.context') }}</h3><template v-if="selected"><strong>{{ titles[selected.conversation_id] || $t('support.consultation') }}</strong><dl><div><dt>{{ $t('expertWorkspace.sessionDetails') }}</dt><dd>{{ $t('support.' + selected.status) }}</dd></div><div><dt>{{ $t('support.request') }}</dt><dd>{{ selected.request_id }}</dd></div><div><dt>{{ $t('expertWorkspace.conversation') }}</dt><dd>{{ selected.conversation_id }}</dd></div><div v-if="selected.requested_at"><dt>{{ $t('expertWorkspace.startedAt') }}</dt><dd>{{ formatDate(selected.requested_at) }}</dd></div><div><dt>{{ $t('expertWorkspace.draftCount') }}</dt><dd>{{ attachmentRows.length }}</dd></div></dl></template><p v-else>{{ $t('support.select') }}</p></section>
          <HistorySearchTool :disabled="!canSearchHistory" @open="openHistorySearch" />
          <ExpertAnnotationTools class="tool-section" :items="savedAnnotations" :draft-rows="attachmentRows" :writable="active" :busy="sending || historyLoading || actionBusy || !!annotationAdding" :loading="annotationHistoryLoading" :more="annotationHistoryMore" :error="annotationHistoryError" @refresh="loadAnnotations(false)" @more="loadAnnotations(true)" @add="addSavedAnnotation" @open="openSavedAnnotation" />
          <AgentToolStatus v-if="selected" :conversation-id="selected.conversation_id" :identity="attachmentIdentity" />
          <SupportSummaryPanel v-if="selected" :key="attachmentScope" :conversation-id="selected.conversation_id" :messages="messages" :writable="active" @source="focusSummarySource" @suggestion="appendSuggestion" />
        </template>
      </ExpertWorkspaceLayout>
    </template>
    <HistorySearchDrawer v-if="historyVisible && selected && profile" :key="attachmentScope" :title="titles[selected.conversation_id] || $t('support.consultation')" :scope-key="attachmentScope" :load-page="searchHistoryPage" @close="closeHistorySearch" @locate="locateHistoryMessage" />
    <el-dialog v-model="transferVisible" :title="$t('conversationManagement.transfer')" width="min(460px,92vw)" :close-on-click-modal="!actionBusy" :close-on-press-escape="!actionBusy" :show-close="!actionBusy">
      <form class="transfer-form" @submit.prevent="confirmTransfer">
        <label>{{ $t('conversationManagement.targetDesk') }}<el-select v-model="transferDraft.service_type" disabled><el-option value="support_agent" :label="$t('conversationManagement.support')" /><el-option value="expert" :label="$t('conversationManagement.experts')" /></el-select></label>
        <label>{{ $t('conversationManagement.targetAgent') }}<el-input v-model="transferDraft.target_agent_user_id" :disabled="actionBusy" maxlength="19" :placeholder="$t('conversationManagement.queueDefault')" /></label>
        <label>{{ $t('conversationManagement.reason') }}<el-input v-model="transferDraft.reason" :disabled="actionBusy" type="textarea" maxlength="500" /></label>
        <p v-if="transferError" role="alert">{{ transferError }}</p>
      </form>
      <template #footer><el-button :disabled="actionBusy" @click="transferVisible = false"><LegacyIcon class="el-icon-circle-close" />{{ $t('conversationManagement.cancel') }}</el-button><el-button type="warning" :disabled="!active || !validTransfer" :loading="actionBusy" @click="confirmTransfer"><LegacyIcon class="el-icon-switch" />{{ $t('conversationManagement.confirmTransfer') }}</el-button></template>
    </el-dialog>
  </section>
</template>
<script>
import MessageAvatar from '../components/MessageAvatar.vue';
import { messageRole, isOwnMessage } from '../utils/messagePresentation.mjs';
import HistorySearchDrawer from '../components/HistorySearchDrawer.vue';
import HistorySearchTool from '../components/HistorySearchTool.vue';
import { readLoadedHistoryPage } from '../utils/historySearch.mjs';
import { mergeMessages } from '../utils/aiStream.mjs';
import AgentToolStatus from '../components/AgentToolStatus.vue';
import ExpertWorkspaceLayout from '../components/expert/ExpertWorkspaceLayout.vue';
import ExpertContactList from '../components/expert/ExpertContactList.vue';
import ExpertAnnotationTools from '../components/expert/ExpertAnnotationTools.vue';
import SupportSummaryPanel from '../components/SupportSummaryPanel.vue';
import MediaSessionOverlay from '../components/MediaSessionOverlay.vue';
import AnnotationEditor from '../components/AnnotationEditor.vue';
import AttachmentComposer from '../components/AttachmentComposer.vue';
import AttachmentPart from '../components/AttachmentPart.vue';
import { mixedMessageParts, attachmentRowsReady, attachmentIdentity } from '../utils/attachments.mjs';
import supportApi from '../apis/module/support';
import conversationApi from '../apis/module/conversation';
import annotationApi from '../apis/module/annotation';
import attachmentApi from '../apis/module/attachment';
import { annotationErrorKey } from '../utils/annotationScene.mjs';
import store from '../store';
import { realtimeAuthorityKey } from '../realtime/identity.mjs';
import { isSupportAdmin, hasSupportRole, supportAccepting, supportDeskPath, allPages, senderKey, cursorInteger } from '../utils/support.mjs';
export default {
  name: 'SupportWorkspace',
  inject: { supportSession: { default: null } },
  props: { serviceType: { type: String, default: 'support_agent', validator: value => ['support_agent', 'expert'].includes(value) } },
  components: { MessageAvatar, HistorySearchTool, HistorySearchDrawer, AgentToolStatus, ExpertWorkspaceLayout, ExpertContactList, ExpertAnnotationTools, SupportSummaryPanel, AttachmentComposer, AttachmentPart, AnnotationEditor, MediaSessionOverlay },
  data: () => ({ callAvailability: null, mediaWorkspaceState: { active: false, hasVideo: false, portrait: false }, staffViewOwner: Symbol('staff-view'), loadedHistoryRequest: '', historyVisible: false, historySearchMessages: [], highlightedHistoryMessageId: '', deskController: null, transferVisible: false, transferDraft: { service_type: 'expert', target_agent_user_id: '', reason: '' }, transferPending: null, transferError: '', annotationHistoryLoading: false, annotationHistoryMore: false, annotationHistoryCursor: '0', annotationHistoryError: '', annotationHistoryEpoch: 0, annotationAdding: '', annotationVisible: false, annotationRecords: {}, attachmentDrafts: {}, mediaAdopting: false, selectionController: null, profile: null, accepting: false, presenceBusy: false, assigned: [], queue: [], selected: null, messages: [], drafts: {}, titles: {}, pending: {}, loading: true, error: '', refreshBusy: false, staffEpoch: 0, permissionCheck: null, actionBusy: false, sending: false, historyLoading: false, historyPolling: false, historyFetchVersion: 0, version: 0, disposed: false, heartbeat: null, poll: null }),
  computed: {
    mediaCallNoticeKey() { const status=this.mediaWorkspaceState.status; return !this.mediaWorkspaceState.visible && ['rejected','canceled','expired','failed','ended'].includes(status) ? 'media.status_' + status : ''; },
    callStatusKey() { const value=this.callAvailability; return 'media.customerStatus_' + (value && value.conversation_id===this.selected?.conversation_id && (value.status !== 'online' || Date.parse(value.expires_at)>Date.now()) ? value.status : 'unavailable'); },
    sessionPresenceBusy() { return this.supportSession?.state.presenceBusy || false; },
    sessionProfile() { return this.supportSession?.state.profile; },
    contactActivity() { return this.supportSession?.state.activity || {}; },
    unreadCounts() { return Object.fromEntries(Object.entries(this.supportSession?.state.unread || {}).map(([id, item]) => [id, item.count])); },
    newRequestBadges() { return this.supportSession?.state.newRequests || {}; },
    canSearchHistory() { return !this.disposed && !!this.selected && this.roleAllowed && (this.selected.service_type || 'support_agent') === this.serviceType && !this.historyLoading && !this.actionBusy; },
    deskIdentity() { return JSON.stringify([realtimeAuthorityKey(store.state), this.serviceType, store.state.userInfo?.superAdmin]); },
    roleAllowed() { return hasSupportRole(this.profile, this.serviceType); },
    validTransfer() { const id = this.transferDraft.target_agent_user_id; return this.transferDraft.reason.length <= 500 && (!id || (/^[1-9][0-9]{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n)); },
    savedAnnotations() {
      const sent = new Set(this.messages.flatMap(message => (message.parts || []).filter(part => part.attachment_id).map(part => String(part.attachment_id))));
      return (this.annotationRecords[this.selected?.request_id] || []).map(item => ({ ...item, sent: sent.has(String(item.annotation?.render_attachment_id || item.row?.attachment?.id)) }));
    },
    mediaUserId() { return String(store.state.userInfo?.id || store.state.userInfo?.user_id || store.state.userInfo?.userId || ''); },
    attachmentIdentity() { return `${attachmentIdentity(store.state)}:${this.serviceType}`; },
    attachmentScope() { return `${this.attachmentIdentity}:${this.selected?.conversation_id || ''}:${this.selected?.request_id || ''}`; },
    attachmentRows: { get() { return this.attachmentDrafts[this.selected?.request_id] || []; }, set(value) { if (this.selected) this.attachmentDrafts[this.selected.request_id] = value; } },
    attachmentsReady() { return attachmentRowsReady(this.attachmentRows); },
    admin() { return isSupportAdmin(store.state.userInfo); },
    agentName() { return store.state.userInfo?.username || this.profile?.user_id || ''; },
    active() { return this.roleAllowed && this.selected?.status === 'active' && (this.selected.service_type || 'support_agent') === this.serviceType && String(this.selected.assigned_agent_user_id || '') === String(this.profile.user_id); },
    draft: { get() { return this.drafts[this.selected?.request_id] || ''; }, set(value) { if (this.selected) this.drafts[this.selected.request_id] = value; } },
  },
  watch: { sessionProfile: { flush: 'sync', handler(profile) { if (!this.supportSession || !this.profile || this.disposed) return; if (!hasSupportRole(profile, this.serviceType)) this.revokeStaff(); else { this.profile = profile; this.accepting = supportAccepting(profile, this.serviceType); } } }, 'selected.request_id'() { this.updateDeskView(); }, attachmentScope: { flush: 'sync', handler() { this.resetHistorySearch(); } }, '$route.query.request'(requestId) { if (typeof requestId !== 'string' || requestId === this.selected?.request_id || !this.profile) return; this.selectionController?.abort(); ++this.version; ++this.historyFetchVersion; this.selected = null; this.messages = []; this.historyLoading = false; this.sending = false; this.annotationVisible = false; this.transferVisible = false; this.refresh(); }, deskIdentity: { flush: 'sync', handler() { this.revokeStaff(); this.drafts = {}; this.pending = {}; this.titles = {}; this.initializeDesk(); } } },
  mounted() { this.initializeDesk(); document.addEventListener('visibilitychange', this.updateDeskView); window.addEventListener('focus', this.updateDeskView); },
  beforeUnmount() { this.disposed = true; this.supportSession?.clearView(this.staffViewOwner); document.removeEventListener('visibilitychange', this.updateDeskView); window.removeEventListener('focus', this.updateDeskView); this.deskController?.abort(); this.selectionController?.abort(); this.version++; clearInterval(this.heartbeat); clearInterval(this.poll); },
  methods: {
    canCall(mode) { const value=this.callAvailability; return !!(this.active && !this.mediaWorkspaceState.active && value?.conversation_id===this.selected?.conversation_id && value.status==='online' && value[mode]===true && Date.parse(value.expires_at)>Date.now()); },
    readingLatest() {
      const list = this.$refs.messageHistory;
      if (!this.selected || !this.roleAllowed || this.disposed || this.historyLoading || this.loadedHistoryRequest !== this.selected.request_id || this.historyVisible || this.highlightedHistoryMessageId || document.visibilityState !== 'visible' || !document.hasFocus() || !list || list.clientHeight <= 0 || list.scrollHeight - list.scrollTop - list.clientHeight > 64) return false;
      const area = list.getBoundingClientRect(), host = list.closest('.expert-content')?.getBoundingClientRect(), last = list.lastElementChild?.getBoundingClientRect();
      const top = Math.max(0, area.top, host?.top || 0), bottom = Math.min(window.innerHeight, area.bottom, host?.bottom || window.innerHeight);
      return !!last && last.bottom > top && last.bottom <= bottom + 1;
    },
    updateDeskView() {
      if (!this.supportSession) return;
      if (!this.selected || !this.roleAllowed || this.disposed) { this.supportSession.clearView(this.staffViewOwner); return; }
      this.supportSession.setView(this.staffViewOwner, {
        requestId: this.selected.request_id, conversationId: this.selected.conversation_id, serviceType: this.serviceType,
        isReading: () => this.readingLatest(), openLatest: () => this.showLatestHistory(),
      });
    },
    resetHistorySearch() { this.historyVisible = false; this.historySearchMessages = []; this.highlightedHistoryMessageId = ''; },
    closeHistorySearch() { this.historyVisible = false; this.historySearchMessages = []; this.$nextTick(() => this.updateDeskView()); },
    openHistorySearch() {
      if (!this.canSearchHistory) return;
      this.closeCollaborationPanels();
      // Search the complete, server-authorized transcript already used by this desk.
      // A released assignment can have a read cutoff below the conversation's last_seq.
      this.historySearchMessages = mergeMessages([], this.messages);
      this.historyVisible = true;
    },
    searchHistoryPage(before, signal) {
      if (!this.historyVisible || !this.canSearchHistory) throw new DOMException('Aborted', 'AbortError');
      return readLoadedHistoryPage(this.historySearchMessages, before, signal);
    },
    async locateHistoryMessage(message) {
      if (!this.historyVisible || !this.canSearchHistory || !this.historySearchMessages.some(row => row.id === message.id && String(row.seq) === String(message.seq)) || !this.messages.some(row => row.id === message.id && String(row.seq) === String(message.seq))) return;
      const version = this.version;
      this.highlightedHistoryMessageId = message.id; this.closeHistorySearch();
      await this.$nextTick();
      if (this.disposed || version !== this.version) return;
      const target = [...(this.$refs.messageHistory?.querySelectorAll('[data-message-id]') || [])].find(row => row.dataset.messageId === String(message.id));
      target?.scrollIntoView({ behavior: 'auto', block: 'center' }); target?.focus({ preventScroll: true });
    },
    async showLatestHistory() {
      const version = this.version; this.closeHistorySearch(); this.highlightedHistoryMessageId = '';
      if (this.selected && !this.historyLoading) await this.loadHistory(false);
      await this.$nextTick();
      if (this.disposed || version !== this.version) return;
      const list = this.$refs.messageHistory, last = list?.lastElementChild;
      if (list) list.scrollTop = list.scrollHeight;
      last?.scrollIntoView({ behavior: 'auto', block: 'nearest' }); last?.focus({ preventScroll: true }); this.updateDeskView();
    },
    async initializeDesk() {
      const epoch = this.staffEpoch; this.loading = true; this.error = '';
      const controller = new AbortController(); this.deskController = controller;
      try {
        const profile = this.supportSession ? await this.supportSession.ready() : await supportApi.me(controller.signal);
        if (this.disposed || epoch !== this.staffEpoch || controller.signal.aborted) return;
        if (!hasSupportRole(profile, this.serviceType)) { this.loading = false; return; }
        this.profile = profile;
        this.accepting = supportAccepting(profile, this.serviceType);
        if (this.roleAllowed && !this.supportSession) await this.updatePresence(this.accepting);
        if (this.disposed || epoch !== this.staffEpoch || !this.profile) return;
        if (this.roleAllowed && !this.supportSession) this.heartbeat = setInterval(() => this.updatePresence(this.accepting), 15000);
        this.poll = setInterval(() => this.refresh(), 4000);
        await this.refresh();
      } catch (error) { if (!this.disposed && epoch === this.staffEpoch && error.name !== 'AbortError') this.error = error.message; }
      finally { if (!this.disposed && epoch === this.staffEpoch) this.loading = false; }
    },
    openTransfer() {
      if (!this.active || this.actionBusy || this.sending) return;
      const target = this.serviceType === 'expert' ? 'support_agent' : 'expert';
      if (this.transferPending?.requestId !== this.selected.request_id) { this.transferDraft = { service_type: target, target_agent_user_id: '', reason: '' }; this.transferPending = null; }
      this.transferError = ''; this.transferVisible = true;
    },
    async confirmTransfer() {
      if (!this.transferVisible || !this.active || !this.validTransfer || this.actionBusy || this.sending) return;
      const request = this.selected, epoch = this.staffEpoch, version = this.version;
      const payload = { service_type: this.transferDraft.service_type, ...(this.transferDraft.target_agent_user_id ? { target_agent_user_id: this.transferDraft.target_agent_user_id } : {}), ...(this.transferDraft.reason.trim() ? { reason: this.transferDraft.reason.trim() } : {}) };
      const signature = JSON.stringify(payload), previous = this.transferPending;
      const clientId = previous?.requestId === request.request_id && previous.signature === signature ? previous.clientId : crypto.randomUUID();
      this.transferPending = { requestId: request.request_id, signature, clientId }; this.actionBusy = true; this.transferError = '';
      try {
        await supportApi.transfer(request.request_id, { ...payload, client_request_id: clientId }, this.selectionController?.signal);
        if (this.disposed || epoch !== this.staffEpoch || version !== this.version) return;
        this.selectionController?.abort(); ++this.version; ++this.historyFetchVersion;
        this.selected = null; this.messages = []; this.annotationRecords = {}; this.annotationVisible = false;
        this.transferVisible = false; this.transferPending = null;
        if (this.$router) this.$router.replace({ path: this.$route.path, query: {} }).catch(() => {});
        await this.refresh();
      } catch (error) { if (!this.disposed && epoch === this.staffEpoch && version === this.version && error.name !== 'AbortError') { this.transferError = error.message; await this.verifyStaffAccess(error); } }
      finally { if (!this.disposed && epoch === this.staffEpoch) this.actionBusy = false; }
    },
    focusSummarySource(ref) { if (this.messages.some(message => message.id === ref.message_id && String(message.seq) === String(ref.seq))) document.getElementById(`support-message-${ref.message_id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }); },
    async mediaChanged(session) { if (session) void this.supportSession?.pollCalls?.(); if (!session || session.agent_user_id !== this.mediaUserId || this.selected?.request_id === session.request_id || !this.profile || this.mediaAdopting) return; this.mediaAdopting = true; try { await this.refresh(); const request = this.assigned.find(item => item.request_id === session.request_id); if (request) await this.selectRequest(request); } finally { this.mediaAdopting = false; } },
    openLiveFrame({ frame, conversationId }) { if (conversationId === this.selected?.conversation_id) this.$refs.annotationEditor?.openFrame(frame.id); },
    closeCollaborationPanels() { this.$refs.expertLayout?.closeTools(); this.$refs.expertLayout?.closeClients(); },
    openAnnotation(source) { this.closeCollaborationPanels(); this.$refs.annotationEditor?.openSource(source); },
    openAnnotationSnapshot(id) { this.closeCollaborationPanels(); this.$refs.annotationEditor?.openSnapshot(id); },
    async selectContact(request) {
      const version = this.version;
      await this.selectRequest(request);
      if (this.version !== version) this.$refs.expertLayout?.closeClients();
    },
    async loadAnnotations(more = false) {
      if (!this.profile || !this.selected || this.disposed || this.annotationHistoryLoading || (more && !this.annotationHistoryMore)) return;
      const version = this.version, scope = this.attachmentScope, request = this.selected, epoch = ++this.annotationHistoryEpoch, after = more ? this.annotationHistoryCursor : '0';
      this.annotationHistoryLoading = true; this.annotationHistoryError = '';
      const current = () => !this.disposed && version === this.version && scope === this.attachmentScope && epoch === this.annotationHistoryEpoch;
      try {
        const page = await annotationApi.list(request.conversation_id, after, this.selectionController?.signal);
        if (!current()) return;
        if (page.has_more && (!/^\d+$/.test(String(page.next_cursor)) || BigInt(page.next_cursor) <= BigInt(after))) throw new Error('INVALID_CURSOR');
        const existing = this.annotationRecords[request.request_id] || [], rows = more ? [...existing] : existing.filter(item => item.row && this.attachmentRows.some(row => row.attachment?.id === item.row.attachment.id));
        for (const annotation of page.items) {
          if (annotation.status !== 'ready' || !annotation.render_attachment_id || (annotation.conversation_id && annotation.conversation_id !== request.conversation_id)) continue;
          const previous = existing.find(item => String(item.annotation?.id || item.row?.attachment?.metadata?.annotation_id) === String(annotation.id));
          const record = { annotation, row: previous?.row || null };
          const index = rows.findIndex(item => String(item.annotation?.id || item.row?.attachment?.metadata?.annotation_id) === String(annotation.id));
          if (index < 0) rows.push(record); else rows[index] = record;
        }
        this.annotationRecords[request.request_id] = rows;
        this.annotationHistoryCursor = String(page.next_cursor || '0'); this.annotationHistoryMore = !!page.has_more;
      } catch (error) {
        if (!current() || error.name === 'AbortError') return;
        this.annotationHistoryError = this.$t(annotationErrorKey(error));
        if ([401,403].includes(error.status)) { this.annotationRecords[request.request_id] = []; this.annotationHistoryMore = false; }
        await this.verifyStaffAccess(error);
      } finally { if (current()) this.annotationHistoryLoading = false; }
    },
    openSavedAnnotation(item) {
      const id = item.annotation?.id || item.row?.attachment?.metadata?.annotation_id;
      if (id && this.savedAnnotations.some(record => String(record.annotation?.id || record.row?.attachment?.metadata?.annotation_id) === String(id))) this.openAnnotationSnapshot(String(id));
    },
    saveAnnotation({ scopeKey, row }) {
      if (scopeKey !== this.attachmentScope || !this.active || this.sending || this.actionBusy || !row?.attachment?.id || (row.attachment.conversation_id && row.attachment.conversation_id !== this.selected.conversation_id)) return;
      const key = this.selected.request_id;
      const items = this.annotationRecords[key] || [], existing = items.find(item => String(item.annotation?.render_attachment_id || item.row?.attachment?.id) === String(row.attachment.id));
      if (existing) existing.row = row;
      else this.annotationRecords[key] = [...items, { row, annotation: row.annotation || null }];
    },
    annotationRenderSent(id) { return id != null && this.messages.some(message => (message.parts || []).some(part => part.attachment_id != null && String(part.attachment_id) === String(id))); },
    async addSavedAnnotation(item) {
      if (!this.active || this.sending || this.historyLoading || this.actionBusy || this.annotationAdding) return;
      const row = item?.attachment ? item : item?.row;
      const renderId = item?.annotation?.render_attachment_id || row?.attachment?.id;
      // A render binds to one message. Reusing a sent annotation requires the
      // editor's authenticated snapshot -> save-copy -> new-render flow.
      if (this.annotationRenderSent(renderId)) {
        const id = item?.annotation?.id || row?.annotation?.id || row?.attachment?.metadata?.annotation_id;
        if (id) this.openAnnotationSnapshot(String(id));
        return;
      }
      if (this.attachmentRows.length >= 10) return;
      // Locally generated ready rows have already passed editor validation.
      if (item?.attachment) { if (this.stageAnnotation({ scopeKey: this.attachmentScope, row: item })) this.$refs.expertLayout?.closeTools(); return; }
      const annotation = item?.annotation;
      if (!annotation?.id || !this.savedAnnotations.some(record => String(record.annotation?.id) === String(annotation.id)) || this.attachmentRows.some(row => String(row.attachment?.id) === String(annotation.render_attachment_id))) return;
      const scope = this.attachmentScope, version = this.version;
      this.annotationAdding = String(annotation.id); this.annotationHistoryError = '';
      const current = () => !this.disposed && version === this.version && scope === this.attachmentScope;
      try {
        const attachment = await attachmentApi.get(annotation.render_attachment_id, this.selectionController?.signal);
        if (!current()) return;
        if (attachment.status !== 'ready' || attachment.conversation_id !== this.selected.conversation_id || String(attachment.metadata?.annotation_id) !== String(annotation.id) || String(attachment.metadata?.frame_id) !== String(annotation.frame_id)) throw new Error('INVALID_SOURCE');
        if (this.stageAnnotation({ scopeKey: scope, row: { kind:'image', clientUploadId:`annotation-${annotation.id}`, status:'ready', progress:100, error:'', attachment, annotation } })) this.$refs.expertLayout?.closeTools();
      } catch (error) { if (current() && error.name !== 'AbortError') { this.annotationHistoryError = this.$t(annotationErrorKey(error)); if ([401,403].includes(error.status)) { this.annotationRecords[this.selected.request_id] = []; this.annotationHistoryMore = false; } await this.verifyStaffAccess(error); } }
      finally { if (current()) this.annotationAdding = ''; }
    },
    stageAnnotation({ scopeKey, row }) {
      if (scopeKey !== this.attachmentScope || this.sending || !this.active || this.actionBusy || !row?.attachment?.id || this.annotationRenderSent(row.attachment.id) || (row.attachment.conversation_id && row.attachment.conversation_id !== this.selected.conversation_id)) return;
      this.saveAnnotation({ scopeKey, row });
      if (this.attachmentRows.some(item => item.attachment?.id === row.attachment.id)) return;
      if (this.attachmentRows.length >= 10) { this.error = this.$t('attachments.count'); return; }
      this.attachmentRows = [...this.attachmentRows,row];
      return true;
    },
    senderKey,
    messageRole,
    messageIsOwn(message) { return isOwnMessage(message, this.profile?.user_id || this.mediaUserId); },
    revokeStaff() {
      this.supportSession?.clearView(this.staffViewOwner); this.loadedHistoryRequest = '';
      this.deskController?.abort(); this.selectionController?.abort(); this.transferVisible = false; this.transferPending = null; this.transferError = ''; this.actionBusy = false; this.refreshBusy = false; this.presenceBusy = false;
      ++this.staffEpoch; ++this.version; ++this.historyFetchVersion; this.historyLoading = false; this.historyPolling = false;
      clearInterval(this.heartbeat); clearInterval(this.poll); this.heartbeat = null; this.poll = null;
      this.annotationRecords = {}; this.annotationVisible = false; this.attachmentDrafts = {}; ++this.annotationHistoryEpoch; this.annotationHistoryLoading = false; this.annotationHistoryMore = false; this.annotationHistoryError = ''; this.annotationAdding = '';
      this.profile = null; this.accepting = false; this.queue = []; this.assigned = []; this.selected = null; this.messages = [];
      this.loading = false; this.sending = false;
    },
    async verifyStaffAccess(error) {
      if (error.status !== 403 || this.disposed || !this.profile) return;
      if (['CURRENT_ASSIGNMENT_REQUIRED', 'ASSIGNMENT_CHANGED'].includes(error.code) && this.selected) { this.selected = { ...this.selected, status: 'RELEASED' }; this.selectionController?.abort(); }
      // A lost assignment is a resource-level 403; only /me determines whether
      // the current account has lost its entire staff profile.
      if (!this.permissionCheck) {
        const epoch = this.staffEpoch;
        this.permissionCheck = (async () => {
          try {
            const profile = await supportApi.me();
            if (this.disposed || epoch !== this.staffEpoch) return;
            if (!hasSupportRole(profile, this.serviceType)) this.revokeStaff();
            else this.profile = profile;
          } catch (checkError) {
            if (!this.disposed && epoch === this.staffEpoch && checkError.status === 403) this.revokeStaff();
          }
        })().finally(() => { this.permissionCheck = null; });
      }
      await this.permissionCheck;
    },
    formatDate(value) { return value ? new Date(value).toLocaleString() : ''; },
    async listAll(fetchPage) {
      const result = []; let cursor = '0';
      while (!this.disposed) {
        const page = await fetchPage(cursor); result.push(...page.items);
        if (!page.has_more) break;
        if (cursorInteger(page.next_cursor) === 0n || (cursor !== '0' && cursorInteger(page.next_cursor) >= cursorInteger(cursor))) throw new Error('历史记录游标异常，请刷新后重试');
        cursor = page.next_cursor;
      }
      return result;
    },
    async updatePresence(value) {
      if (this.presenceBusy || this.sessionPresenceBusy || !this.roleAllowed || this.disposed) return;
      this.presenceBusy = true; const epoch = this.staffEpoch;
      try { const profile = this.supportSession ? await this.supportSession.setAccepting(value, this.serviceType) : await supportApi.presence(value, this.serviceType, this.deskController?.signal); if (!this.disposed && epoch === this.staffEpoch) { this.profile = profile; this.accepting = supportAccepting(profile, this.serviceType); } }
      catch (error) { if (!this.disposed && epoch === this.staffEpoch) { this.error = error.message; this.profile = this.supportSession?.state.profile || this.profile; this.accepting = supportAccepting(this.profile, this.serviceType); await this.verifyStaffAccess(error); } }
      finally { if (epoch === this.staffEpoch) this.presenceBusy = false; }
    },
    async refresh() {
      if (this.refreshBusy || this.disposed) return; this.refreshBusy = true; const refreshEpoch = this.staffEpoch;
      try {
        if (this.profile) {
          const epoch = this.staffEpoch;
          let queue, assigned;
          try { [queue, assigned] = await Promise.all([this.listAll(cursor => supportApi.queue(cursor, this.serviceType, this.deskController?.signal)), this.listAll(cursor => supportApi.assigned(cursor, this.serviceType, this.deskController?.signal))]); }
          catch (error) { await this.verifyStaffAccess(error); throw error; }
          if (this.disposed || epoch !== this.staffEpoch) return; this.queue = queue.filter(item => (item.service_type || 'support_agent') === this.serviceType); this.assigned = assigned.filter(item => (item.service_type || 'support_agent') === this.serviceType); assigned = this.assigned;
          if (this.selected && !this.sending && !this.historyLoading && !this.actionBusy) {
            const request = assigned.find(item => item.request_id === this.selected.request_id);
            if (request) { this.selected = request; await this.loadHistory(false); }
            else if (this.active) this.selected = { ...this.selected, status: 'RELEASED' };
          }
          if (!this.selected && !this.mediaAdopting && !this.historyLoading && !this.actionBusy) {
            const requestId = this.$route?.query?.request;
            const owned = typeof requestId === 'string' && assigned.find(item => item.request_id === requestId);
            if (owned) await this.selectRequest(owned);
            else if (typeof requestId === 'string') {
              try {
                const request = await supportApi.get(requestId, this.deskController?.signal);
                if (this.disposed || epoch !== this.staffEpoch) return;
                if ((request.service_type || 'support_agent') !== this.serviceType) {
                  if (this.$router) await this.$router.replace({ path: supportDeskPath(request.service_type), query: { request: requestId } });
                } else if (this.admin || String(request.assigned_agent_user_id) === String(this.profile.user_id)) await this.selectRequest(request);
              } catch (error) { if (!this.disposed && epoch === this.staffEpoch && error.name !== 'AbortError') this.error = error.message; }
            }
          }
        }
      } catch (error) { if (!this.disposed && refreshEpoch === this.staffEpoch && error.name !== 'AbortError') this.error = error.message; }
      finally { if (refreshEpoch === this.staffEpoch) this.refreshBusy = false; }
    },
    async selectRequest(request, force = false) { if ((request.service_type || 'support_agent') !== this.serviceType || !this.profile || this.sending || this.historyLoading || (this.actionBusy && !force)) return; this.selectionController?.abort(); this.selectionController = new AbortController(); this.loadedHistoryRequest = ''; this.selected = request; this.messages = []; ++this.annotationHistoryEpoch; this.annotationHistoryLoading = false; this.annotationHistoryMore = false; this.annotationHistoryCursor = '0'; this.annotationHistoryError = ''; this.annotationAdding = ''; ++this.historyFetchVersion; this.historyPolling = false; const version = ++this.version;
      if (this.$router && this.$route?.query?.request !== request.request_id) this.$router.replace({ path: this.$route.path, query: { ...this.$route.query, request: request.request_id } }).catch(() => {});
      const title = conversationApi.detail(request.conversation_id).then(detail => { if (!this.disposed && version === this.version) this.titles[request.conversation_id] = detail.title; }).catch(error => this.verifyStaffAccess(error));
      await Promise.all([this.loadHistory(true), this.loadAnnotations(false), title]);
      if (!this.disposed && version === this.version && this.$route?.query?.focus === 'message') {
        await this.showLatestHistory();
        const { focus, ...query } = this.$route.query;
        if (this.$router && version === this.version) void this.$router.replace({ path: this.$route.path, query }).catch(() => {});
      }
    },
    async loadHistory(reset) {
      if (!this.selected || this.historyLoading || (!reset && this.historyPolling)) return;
      const version = this.version, request = this.selected, fetchVersion = ++this.historyFetchVersion;
      if (reset) this.historyLoading = true; else this.historyPolling = true;
      try {
        const after = reset ? '0' : (this.messages.at(-1)?.seq || '0');
        const page = await allPages(cursor => conversationApi.messages(request.conversation_id, cursor), after);
        if (version !== this.version || this.disposed) return;
        const follow = reset || this.readingLatest();
        for (const message of page.items) if (!this.messages.some(item => item.id === message.id)) this.messages.push(message);
        this.loadedHistoryRequest = request.request_id;
        await this.$nextTick();
        if (version === this.version && !this.disposed && follow && this.$refs.messageHistory) this.$refs.messageHistory.scrollTop = this.$refs.messageHistory.scrollHeight;
      } catch (error) { if (version === this.version) this.error = error.message; await this.verifyStaffAccess(error); }
      finally { if (fetchVersion === this.historyFetchVersion) { if (reset) this.historyLoading = false; else this.historyPolling = false; this.updateDeskView(); } }
    },
    async claim(request) {
      if (this.actionBusy || this.historyLoading || !this.roleAllowed || (request.service_type || 'support_agent') !== this.serviceType || !this.accepting) return; this.actionBusy = true; this.error = ''; const epoch = this.staffEpoch;
      try { const claimed = await supportApi.action(request.request_id, 'claim'); if (this.disposed || epoch !== this.staffEpoch) return; await this.selectRequest(claimed, true); this.$refs.expertLayout?.closeClients(); await this.refresh(); }
      catch (error) { if (!this.disposed && epoch === this.staffEpoch && error.name !== 'AbortError') { this.error = error.message; await this.verifyStaffAccess(error); } } finally { if (epoch === this.staffEpoch) this.actionBusy = false; }
    },
    async end(action) {
      if (!this.active || this.actionBusy || this.sending) return; this.actionBusy = true; const epoch = this.staffEpoch;
      try { const selected = await supportApi.action(this.selected.request_id, action); if (this.disposed || epoch !== this.staffEpoch) return; this.selected = selected; await this.refresh(); }
      catch (error) { if (!this.disposed && epoch === this.staffEpoch && error.name !== 'AbortError') { this.error = error.message; await this.verifyStaffAccess(error); } } finally { if (epoch === this.staffEpoch) this.actionBusy = false; }
    },
    composerKeydown(event) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); this.send(); } },
    appendSuggestion(suggestion) {
      if (!this.active || this.sending || this.disposed || suggestion?.conversation_id !== this.selected?.conversation_id || typeof suggestion.text !== 'string' || !suggestion.text.trim()) return;
      const next = [this.draft, suggestion.text.trim()].filter(Boolean).join('\n\n');
      if (next.length > 8000) { this.error = '回复草稿最多8000字，请先整理现有内容后再加入建议。'; return; }
      this.draft = next;
    },
    async send() {
      if (!this.active || this.actionBusy || this.sending || (!this.draft.trim() && !this.attachmentRows.length) || !this.attachmentsReady || this.historyLoading) return;
      const request = this.selected, text = this.draft, previous = this.pending[request.request_id], version = this.version;
      const rows = [...this.attachmentRows], parts = mixedMessageParts(text, rows), signature = JSON.stringify(parts);
      const clientId = previous?.signature === signature ? previous.clientId : crypto.randomUUID();
      this.pending[request.request_id] = { text, signature, clientId }; this.sending = true; this.error = '';
      try {
        const message = await supportApi.send(request.request_id, { client_message_id: clientId, parts }, this.selectionController?.signal);
        if (!this.disposed && version === this.version && this.selected?.request_id === request.request_id) { if (!this.messages.some(item => item.id === message.id)) this.messages.push(message); if (this.draft === text) this.draft = ''; this.attachmentRows = this.attachmentRows.filter(row => !rows.some(sent => sent.clientUploadId === row.clientUploadId)); }
        if (version === this.version) delete this.pending[request.request_id];
      } catch (error) { if (version === this.version && !this.disposed && error.name !== 'AbortError') { this.error = error.message; await this.verifyStaffAccess(error); } } finally { if (version === this.version) this.sending = false; }
    },

  },
};
</script>
<style scoped lang="scss">
@use '../styles/chat-messages' as messages;
.history-search-entry{display:inline-flex;align-items:center;justify-content:center;gap:6px;border:0;background:transparent;color:var(--el-color-primary);font:inherit;font-size:var(--admin-font-xs);padding:8px 0;cursor:pointer}.history-search-entry:disabled{color:var(--el-text-color-disabled);cursor:not-allowed}.chat-heading .history-search-entry{margin-left:auto}.history-search-entry:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.history-context-bar{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:8px 18px;background:var(--el-color-primary-light-9);font-size:var(--admin-font-xs);flex-shrink:0}.history-context-bar button{border:0;background:transparent;color:var(--el-color-primary);font:inherit;font-size:var(--admin-font-xs);cursor:pointer}.history article.history-target .message-content{box-shadow:0 0 0 2px var(--el-color-primary-light-5);background:var(--el-color-primary-light-9)}.history article.history-target:focus{outline:none}
.transfer-form{display:grid;gap:16px}.transfer-form label{display:grid;gap:8px}.desk-counts{font-size:var(--admin-font-xs);color:var(--el-text-color-secondary)}
.support-workspace { padding:0; width:100%; margin:0; text-align:left; color:var(--admin-text, #253247); }.workspace-heading { display:flex; align-items:center; justify-content:space-between; gap:20px; margin:0 0 18px; }.eyebrow { font-size:var(--admin-font-xs); color:var(--el-text-color-secondary, #909399); letter-spacing:1px; }h1 { font-size:var(--admin-font-title); margin:4px 0 0; font-weight:650; }.presence { display:flex; gap:14px; align-items:center; flex-wrap:wrap; }.presence strong { font-size:var(--admin-font-base); }.presence-state { display:flex; align-items:center; gap:6px; font-size:var(--admin-font-xs); color:var(--el-text-color-secondary, #909399); }.presence-state i { width:7px; height:7px; border-radius:50%; background:var(--el-text-color-secondary, #909399); }.presence-state.working { color:var(--el-color-primary, #409eff); }.presence-state.working i { background:var(--el-color-success, #67c23a); }.conversation-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:18px 20px; background:white; border-bottom:1px solid var(--admin-border, #e5e9f0); flex:none; }.conversation-heading h2 { font-size:var(--admin-font-title); margin:0 0 6px; overflow-wrap:anywhere; }.conversation-heading p { font-size:var(--admin-font-xs); margin:0; color:var(--el-text-color-secondary, #909399); line-height:1.7; }.request-id { overflow-wrap:anywhere; font-size:var(--admin-font-xs); }.status-dot { display:inline-block; width:6px; height:6px; border-radius:50%; background:var(--el-color-primary, #409eff); margin-right:6px; }.session-actions { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:6px; flex:none; }.session-actions .el-button { margin:0; font-size:var(--admin-font-xs); padding:8px; height:auto; }.media-region { position:relative; min-height:340px; height:auto; max-height:none; flex:0 0 auto; background:var(--admin-background, #f4f6f9); overflow:visible; isolation:isolate; }.media-region.annotation-visible { min-height:420px; }.chat { display:flex; flex:1; flex-direction:column; min-height:340px; background:white; }.chat-heading { display:flex; gap:8px; align-items:center; padding:12px 18px; border-bottom:1px solid var(--admin-border, #e5e9f0); }.chat-heading h3 { margin:0; font-size:var(--admin-font-md); }.chat-heading small { color:var(--el-text-color-secondary, #909399); }.history { min-height:80px; max-height:280px; flex:1; overflow:auto; padding:10px 18px; overscroll-behavior:contain; }article { margin:0 0 14px; }article>small { display:block; font-size:var(--admin-font-xs); color:var(--el-text-color-secondary, #909399); margin-bottom:5px; }.message-content { display:inline-block; padding:9px 12px; max-width:95%; background:var(--admin-background, #f4f6f9); border:1px solid var(--admin-border, #e5e9f0); border-radius:0 9px 9px 9px; }.message-content p { white-space:pre-wrap; overflow-wrap:anywhere; font-size:var(--admin-font-base); line-height:1.7; margin:0; }.staff-message { text-align:right; }.staff-message .message-content { background:var(--el-color-primary-light-9, #ecf5ff); border-color:var(--admin-border, #e5e9f0); border-radius:9px 0 9px 9px; text-align:left; }.chat-empty { font-size:var(--admin-font-base); line-height:1.8; color:var(--el-text-color-secondary, #909399); padding:18px; text-align:center; margin:0; }.composer { padding:0 16px 12px; border-top:1px solid var(--admin-border, #e5e9f0); }.composer-footer { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-top:9px; }.composer-footer small,.retry-note { font-size:var(--admin-font-xs); color:var(--el-text-color-secondary, #909399); line-height:1.6; }.retry-note { margin:7px 0 0; }.composer :deep(.el-textarea__inner:focus) { box-shadow:0 0 0 1px var(--el-color-primary, #409eff) inset; }.composer :deep(.el-button--primary) { background:var(--el-color-primary, #409eff); border-color:var(--el-color-primary, #409eff); }.tool-section { padding-bottom:18px; margin-bottom:18px; border-bottom:1px solid var(--admin-border, #e5e9f0); }.tool-section h3 { font-size:var(--admin-font-md); margin:0 0 12px; }.context>strong { font-size:var(--admin-font-base); }.context p { color:var(--el-text-color-secondary, #909399); font-size:var(--admin-font-xs); line-height:1.7; }.context dl { margin:14px 0 0; display:grid; gap:12px; }.context dl>div { display:flex; justify-content:space-between; gap:10px; font-size:var(--admin-font-xs); }.context dt { flex:none; color:var(--el-text-color-secondary, #909399); }.context dd { margin:0; text-align:right; overflow-wrap:anywhere; }
@media(max-width:1000px) { .workspace-heading { align-items:flex-start; }.presence { gap:8px; justify-content:flex-end; }.presence :deep(.el-switch__label) { display:none; }.conversation-heading { padding:14px; }.session-actions { flex-direction:column; } }
@media(max-width:760px) { .workspace-heading { margin-bottom:12px; gap:10px; }h1 { font-size:var(--admin-font-title); }.eyebrow { font-size:var(--admin-font-xs); }.presence { gap:6px; }.presence strong { display:none; }.presence-state { font-size:var(--admin-font-xs); }.conversation-heading h2 { font-size:var(--admin-font-md); }.conversation-heading { padding:12px; }.request-id { display:none; }.media-region { height:auto; min-height:300px; }.media-region.annotation-visible { min-height:400px; }.history { max-height:250px; }.composer { padding:0 12px 12px; }.composer-footer small { max-width:65%; } }

.support-workspace { display:flex; flex-direction:column; height:100%; min-height:0; }
.workspace-heading { flex:none; margin-bottom:10px; min-height:36px; gap:12px; }
.workspace-heading .eyebrow { display:none; }
h1 { font-size:var(--admin-font-title); margin:0; }


.conversation-heading { padding:12px 16px; }
.conversation-heading h2 { font-size:var(--admin-font-md); margin-bottom:4px; }
.media-region.annotation-visible { min-height:0; }

.chat { min-height:290px; }
.composer { flex:none; max-height:44%; overflow-y:auto; background:var(--el-bg-color, #fff); }
.composer :deep(.attachment-list) { max-height:min(16dvh, 112px); overflow-y:auto; }
.composer :deep(.el-textarea__inner) { max-height:clamp(52px, 16dvh, 144px); }
@media(max-width:760px) {
  .workspace-heading { margin-bottom:6px; min-height:34px; }
  h1 { font-size:var(--admin-font-title); }
  .presence { gap:5px; }
  .presence-state { font-size:var(--admin-font-xs); }
  .conversation-heading { padding:10px 12px; }
  .conversation-heading h2 { font-size:var(--admin-font-md); }


}
@media(max-width:760px) {
  .history .chat-message { display:flex; align-items:flex-start; gap:8px; }
  .history .staff-message { flex-direction:row-reverse; }
  .history .thread-body { flex:1; min-width:0; }
  .history .thread-meta { display:block; margin-bottom:5px; font-size:var(--admin-font-xs); color:var(--el-text-color-secondary); }
}
/* Media, editing, transcript and composer keep independent scroll boundaries. */
.media-region { display:flex; flex-direction:column; min-height:0; height:100%; overflow:hidden; }
.chat { min-height:0; flex:1 1 0; overflow:hidden; }
.chat-heading { flex:none; }
.history { flex:1 1 0; min-height:0; max-height:none; }
.annotation-region { display:flex; flex-direction:column; height:100%; min-width:0; min-height:0; overflow:hidden; box-sizing:border-box; background:var(--el-bg-color,#fff); border-bottom:1px solid var(--admin-border,#e5e9f0); }
.annotation-region :deep(.annotation-workspace--docked) { flex:1 1 0; height:0; min-height:0; }
.frozen-frame-caption { flex:none; margin:0; padding:8px 12px; font-size:var(--admin-font-xs); color:var(--el-text-color-secondary,#909399); background:var(--el-color-primary-light-9,#ecf5ff); }
.support-workspace :deep(.collaboration-pane:not(.has-media) .media-region),
.support-workspace :deep(.collaboration-pane:not(.has-media) .media-workspace--adaptive),
.support-workspace :deep(.collaboration-pane.is-preparing .media-region),
.support-workspace :deep(.collaboration-pane.is-preparing .media-workspace--adaptive) { height:auto; }
/* The compact live-call strip keeps controls reachable while editing on narrow workspaces. */
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-overlay) { padding:4px; overflow:auto; }
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-overlay > header),
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-overlay > small),
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-status),
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-launch),
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-note),
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-transfer-form) { display:none; }
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-stage-host) { flex:0 0 64px; min-height:64px; max-height:64px; margin:0 0 4px; }
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-session-controls) { flex:none; gap:4px; }
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-actions) { display:contents; }
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-hangup) { margin-top:0; }
@include messages.thread;

.workspace-heading { margin-bottom:6px; min-height:32px; }
.reception-control { padding:3px 10px; }
.conversation-heading { padding:8px 12px; }
</style>

<style scoped>
.workspace-heading { flex-wrap:wrap; }
.presence { gap:10px; }
.desk-counts { display:flex; align-items:center; flex-wrap:wrap; gap:6px; }
.desk-count { display:inline-flex; align-items:center; gap:5px; padding:5px 8px; border-radius:6px; font-size:var(--admin-font-xs,12px); white-space:nowrap; }
.desk-count.waiting { color:var(--el-color-warning-dark-2,#b88230); background:var(--el-color-warning-light-9,#fdf6ec); }
.desk-count.assigned { color:var(--el-color-primary-dark-2,#337ecc); background:var(--el-color-primary-light-9,#ecf5ff); }
.reception-control { display:flex; align-items:center; gap:12px; padding:5px 12px; border:1px solid; border-radius:9px; transition:background-color .15s,border-color .15s; }
.reception-label { display:inline-flex; align-items:center; gap:7px; font-size:var(--admin-font-base,14px); font-weight:600; white-space:nowrap; }
.reception-control.is-accepting { color:var(--el-color-success-dark-2,#529b2e); border-color:var(--el-color-success-light-5,#b3e19d); background:var(--el-color-success-light-9,#f0f9eb); }
.reception-control.is-paused { color:var(--el-color-warning-dark-2,#b88230); border-color:var(--el-color-warning-light-5,#f3d19e); background:var(--el-color-warning-light-9,#fdf6ec); }
.reception-control :deep(.el-switch) { --el-switch-on-color:var(--el-color-success); --el-switch-off-color:var(--el-color-warning); }
.reception-label .el-icon { font-size:18px; }
.conversation-heading { flex-wrap:wrap; }
.conversation-heading>div:first-child { flex:1 1 240px; min-width:0; }
.conversation-heading .session-actions { flex:0 1 auto; flex-direction:row; align-items:center; max-width:100%; gap:7px; }
.session-actions .el-button { min-height:32px; padding:7px 10px; border-radius:6px; font-weight:500; }
.session-actions :deep(.el-button>span),.composer-footer :deep(.el-button>span) { gap:5px; }
.call-availability { display:inline-flex; align-items:center; gap:5px; padding:5px 8px; border-radius:6px; background:var(--el-fill-color-light,#f5f7fa); color:var(--el-text-color-secondary,#909399); font-size:var(--admin-font-xs,12px); white-space:nowrap; }
.call-availability.is-online { color:var(--el-color-success-dark-2,#529b2e); background:var(--el-color-success-light-9,#f0f9eb); }
.call-availability.is-busy { color:var(--el-color-warning-dark-2,#b88230); background:var(--el-color-warning-light-9,#fdf6ec); }
.call-result { display:inline-flex; align-items:center; gap:5px; color:var(--el-text-color-secondary,#909399); font-size:var(--admin-font-xs,12px); }
.support-workspace :deep(.collaboration-pane.has-media.is-stacked.has-annotation .media-audio-summary) { display:none; }
@media(max-width:760px) { .presence { gap:6px; }.reception-control { gap:8px; padding:3px 8px; }.reception-label { font-size:var(--admin-font-xs,12px); }.conversation-heading .session-actions { justify-content:flex-start; }.session-actions .el-button { min-height:34px; } }
</style>
