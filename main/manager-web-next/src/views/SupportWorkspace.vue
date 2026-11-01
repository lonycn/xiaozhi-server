<template>
  <section class="support-workspace">
    <el-alert v-if="error" :title="error" type="error" show-icon @close="error = ''" />
    <p v-if="loading" role="status">{{ $t('support.loading') }}</p>
    <p v-if="!loading && !profile" role="status">{{ admin ? `${$t('support.agent')} · ${$t('knowledgeBaseDialog.statusDisabled')}` : $t('support.denied') }}</p>
    <template v-if="profile">
      <header class="workspace-heading"><div><span class="eyebrow">{{ $t('expertWorkspace.brand') }}</span><h1>{{ $t('expertWorkspace.workspace') }}</h1></div><div class="presence"><span class="presence-state" :class="{ working: accepting }"><i />{{ $t(accepting ? 'expertWorkspace.working' : 'expertWorkspace.paused') }}</span><strong>{{ agentName }}</strong><el-switch :aria-label="$t('support.accepting')" v-model="accepting" :disabled="presenceBusy" :active-text="$t('support.accepting')" :inactive-text="$t('support.paused')" @change="updatePresence" /></div></header>
      <ExpertWorkspaceLayout ref="expertLayout">
        <template #contacts><ExpertContactList :assigned="assigned" :queue="queue" :titles="titles" :selected-id="selected?.request_id || ''" :accepting="accepting" :busy="sending || historyLoading || actionBusy" :refreshing="refreshBusy" @select="selectContact" @claim="claim" @refresh="refresh" /></template>
        <header class="conversation-heading"><div><h2>{{ selected ? (titles[selected.conversation_id] || $t('support.consultation')) : $t('expertWorkspace.noSelection') }}</h2><p v-if="selected"><span class="status-dot" />{{ $t('support.' + selected.status) }}<span class="request-id" :title="selected.request_id"> · {{ selected.request_id }}</span></p><p v-else>{{ $t('expertWorkspace.selectHint') }}</p></div><div v-if="selected" class="session-actions"><el-button :disabled="actionBusy || sending || !active" @click="end('resolve')">{{ $t('support.resolve') }}</el-button><el-button :disabled="actionBusy || sending || !active" @click="end('requeue')">{{ $t('support.requeue') }}</el-button></div></header>
        <section class="media-region" :class="{ 'annotation-visible': annotationVisible }" :aria-label="$t('expertWorkspace.mediaIdle')">
          <MediaSessionOverlay :conversation-id="selected?.conversation_id || ''" :request-id="active ? selected.request_id : ''" :identity="attachmentIdentity" :user-id="mediaUserId" staff :writable="active" embedded :capture-disabled="annotationVisible" @changed="mediaChanged" @frame="openLiveFrame"><template #stage-overlay>
          <AnnotationEditor ref="annotationEditor" :conversation-id="selected?.conversation_id || ''" :scope-key="attachmentScope" :identity="attachmentIdentity" :writable="active" :disabled="sending || actionBusy || historyLoading" embedded @visibility="annotationVisible = $event" @ready="stageAnnotation" />
          </template></MediaSessionOverlay>
        </section>
        <section class="chat" :aria-label="$t('expertWorkspace.chat')">
          <div class="chat-heading"><h3>{{ $t('expertWorkspace.chat') }}</h3><small>{{ selected ? messages.length : '—' }}</small></div>
          <template v-if="selected">
            <p v-if="historyLoading" role="status">{{ $t('support.loading') }}</p>
            <div class="history" aria-live="polite"><p v-if="!messages.length && !historyLoading" class="chat-empty">{{ $t('expertWorkspace.chatEmpty') }}</p><article v-for="message in messages" :key="message.id" :id="`support-message-${message.id}`" :class="{ 'staff-message': ['support_agent', 'expert'].includes(message.sender_type) }"><small>{{ $t('support.' + senderKey(message.sender_type)) }} · {{ formatDate(message.created_at) }}</small><div class="message-content"><template v-for="(part, index) in message.parts" :key="index"><p v-if="part.type === 'text'">{{ part.text }}</p><AttachmentPart v-else-if="['image', 'video', 'audio', 'file'].includes(part.type) && part.attachment_id" :part="part" :scope-key="attachmentScope" :can-annotate="active && !sending && !historyLoading && !actionBusy" @annotate="openAnnotation" @snapshot="openAnnotationSnapshot" /><p v-else-if="part.type === 'location'">{{ part.latitude }}, {{ part.longitude }} · {{ part.coordinate_system }}</p><p v-else>{{ $t('support.nonText') }}</p></template></div></article></div>
            <div class="composer"><AttachmentComposer :key="attachmentScope" v-model="attachmentRows" :conversation-id="selected.conversation_id" :identity="attachmentIdentity" :disabled="sending || !active || historyLoading" /><form @submit.prevent="send"><el-input v-model="draft" type="textarea" :rows="2" maxlength="8000" :disabled="sending || !active" :aria-label="$t('support.reply')" @keydown="composerKeydown" /><div class="composer-footer"><small>{{ $t(active ? 'expertWorkspace.sendHint' : 'expertWorkspace.readonly') }}</small><el-button native-type="submit" type="primary" :loading="sending" :disabled="!active || (!draft.trim() && !attachmentRows.length) || !attachmentsReady || historyLoading">{{ $t('support.reply') }}</el-button></div><p class="retry-note">{{ $t('support.retryNote') }}</p></form></div>
          </template><p v-else class="chat-empty">{{ $t('expertWorkspace.selectHint') }}</p>
        </section>
        <template #tools>
          <section class="tool-section context"><h3>{{ $t('expertWorkspace.context') }}</h3><template v-if="selected"><strong>{{ titles[selected.conversation_id] || $t('support.consultation') }}</strong><dl><div><dt>{{ $t('expertWorkspace.sessionDetails') }}</dt><dd>{{ $t('support.' + selected.status) }}</dd></div><div><dt>{{ $t('support.request') }}</dt><dd>{{ selected.request_id }}</dd></div><div><dt>{{ $t('expertWorkspace.conversation') }}</dt><dd>{{ selected.conversation_id }}</dd></div><div v-if="selected.requested_at"><dt>{{ $t('expertWorkspace.startedAt') }}</dt><dd>{{ formatDate(selected.requested_at) }}</dd></div><div><dt>{{ $t('expertWorkspace.draftCount') }}</dt><dd>{{ attachmentRows.length }}</dd></div></dl></template><p v-else>{{ $t('support.select') }}</p></section>
          <ExpertAnnotationTools class="tool-section" :items="savedAnnotations" :draft-rows="attachmentRows" :writable="active" :busy="sending || historyLoading || actionBusy || !!annotationAdding" :loading="annotationHistoryLoading" :more="annotationHistoryMore" :error="annotationHistoryError" @refresh="loadAnnotations(false)" @more="loadAnnotations(true)" @add="addSavedAnnotation" @open="openSavedAnnotation" />
          <SupportSummaryPanel v-if="selected" :key="attachmentScope" :conversation-id="selected.conversation_id" :messages="messages" :writable="active" @source="focusSummarySource" @suggestion="appendSuggestion" />
        </template>
      </ExpertWorkspaceLayout>
    </template>
    <details v-if="admin" class="admin"><summary>{{ $t('expertWorkspace.expandAdmin') }}</summary>
      <h2>{{ $t('support.profiles') }}</h2><p>{{ $t('support.profileNote') }}</p>
      <form class="toolbar" @submit.prevent="saveProfile"><el-input v-model="edit.user_id" :aria-label="$t('support.userId')" :placeholder="$t('support.userId')" /><el-select v-model="edit.role" :aria-label="$t('support.role')"><el-option value="support_agent" :label="$t('support.agent')" /><el-option value="expert" :label="$t('support.expert')" /></el-select><el-switch :aria-label="$t('support.enabled')" v-model="edit.enabled" :active-text="$t('support.enabled')" /><el-input-number v-model="edit.capacity_text" :min="1" :max="20" :aria-label="$t('support.capacity')" /><el-button native-type="submit" type="primary" :loading="saving" :disabled="!/^\d+$/.test(edit.user_id)">{{ $t('support.save') }}</el-button></form>
      <el-table :data="agents"><el-table-column :label="$t('support.username')"><template #default="{ row }">{{ row.username }}<el-tag v-if="row.account_enabled === false" type="danger" size="small">{{ $t('knowledgeBaseDialog.statusDisabled') }}</el-tag></template></el-table-column><el-table-column prop="user_id" :label="$t('support.userId')" /><el-table-column :label="$t('support.role')"><template #default="{ row }">{{ $t(row.role === 'expert' ? 'support.expert' : 'support.agent') }}</template></el-table-column><el-table-column :label="$t('support.enabled')"><template #default="{ row }">{{ $t(row.enabled ? 'knowledgeBaseDialog.statusEnabled' : 'knowledgeBaseDialog.statusDisabled') }}</template></el-table-column><el-table-column prop="capacity_text" :label="$t('support.capacity')" /><el-table-column :label="$t('support.edit')"><template #default="{ row }"><el-button @click="edit = { user_id: row.user_id, role: row.role, enabled: row.enabled, capacity_text: row.capacity_text }">{{ $t('support.edit') }}</el-button></template></el-table-column></el-table>
    </details>
  </section>
</template>
<script>
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
import { isSupportAdmin, allPages, senderKey, cursorInteger } from '../utils/support.mjs';
export default {
  name: 'SupportWorkspace',
  components: { ExpertWorkspaceLayout, ExpertContactList, ExpertAnnotationTools, SupportSummaryPanel, AttachmentComposer, AttachmentPart, AnnotationEditor, MediaSessionOverlay },
  data: () => ({ annotationHistoryLoading: false, annotationHistoryMore: false, annotationHistoryCursor: '0', annotationHistoryError: '', annotationHistoryEpoch: 0, annotationAdding: '', annotationVisible: false, annotationRecords: {}, attachmentDrafts: {}, mediaAdopting: false, selectionController: null, profile: null, accepting: false, presenceBusy: false, assigned: [], queue: [], agents: [], selected: null, messages: [], drafts: {}, titles: {}, pending: {}, loading: true, error: '', refreshBusy: false, staffEpoch: 0, permissionCheck: null, actionBusy: false, sending: false, saving: false, historyLoading: false, historyPolling: false, historyFetchVersion: 0, version: 0, disposed: false, heartbeat: null, poll: null, edit: { user_id: '', role: 'support_agent', enabled: true, capacity_text: 1 } }),
  computed: {
    savedAnnotations() {
      const sent = new Set(this.messages.flatMap(message => (message.parts || []).filter(part => part.attachment_id).map(part => String(part.attachment_id))));
      return (this.annotationRecords[this.selected?.request_id] || []).map(item => ({ ...item, sent: sent.has(String(item.annotation?.render_attachment_id || item.row?.attachment?.id)) }));
    },
    mediaUserId() { return String(store.state.userInfo?.id || store.state.userInfo?.user_id || store.state.userInfo?.userId || ''); },
    attachmentIdentity() { return attachmentIdentity(store.state); },
    attachmentScope() { return `${this.attachmentIdentity}:${this.selected?.conversation_id || ''}:${this.selected?.request_id || ''}`; },
    attachmentRows: { get() { return this.attachmentDrafts[this.selected?.request_id] || []; }, set(value) { if (this.selected) this.attachmentDrafts[this.selected.request_id] = value; } },
    attachmentsReady() { return attachmentRowsReady(this.attachmentRows); },
    admin() { return isSupportAdmin(store.state.userInfo); },
    agentName() { return store.state.userInfo?.username || this.profile?.user_id || ''; },
    active() { return !!this.profile && this.selected?.status === 'active'; },
    draft: { get() { return this.drafts[this.selected?.request_id] || ''; }, set(value) { if (this.selected) this.drafts[this.selected.request_id] = value; } },
  },
  watch: { attachmentIdentity() { this.selectionController?.abort(); this.annotationRecords = {}; this.annotationVisible = false; this.attachmentDrafts = {}; this.drafts = {}; this.pending = {}; this.agents = []; this.titles = {}; this.revokeStaff(); } },
  async mounted() {
    const epoch = this.staffEpoch;
    try { const profile = await supportApi.me(); if (this.disposed || epoch !== this.staffEpoch) return; this.profile = profile; } catch (error) { if (!this.disposed && epoch === this.staffEpoch && ![403, 404].includes(error.status)) this.error = error.message; }
    if (this.disposed || epoch !== this.staffEpoch) return;
    if (this.profile) { this.accepting = false; await this.updatePresence(false); if (this.disposed || !this.profile) return; this.heartbeat = setInterval(() => this.updatePresence(this.accepting), 15000); this.poll = setInterval(() => this.refresh(), 4000); }
    await this.refresh(); this.loading = false;
  },
  beforeUnmount() { this.disposed = true; this.selectionController?.abort(); this.version++; clearInterval(this.heartbeat); clearInterval(this.poll); if (this.profile) supportApi.presence(false).catch(() => {}); },
  methods: {
    focusSummarySource(ref) { if (this.messages.some(message => message.id === ref.message_id && String(message.seq) === String(ref.seq))) document.getElementById(`support-message-${ref.message_id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' }); },
    async mediaChanged(session) { if (!session || session.agent_user_id !== this.mediaUserId || this.selected?.request_id === session.request_id || !this.profile || this.mediaAdopting) return; this.mediaAdopting = true; try { await this.refresh(); const request = this.assigned.find(item => item.request_id === session.request_id); if (request) await this.selectRequest(request); } finally { this.mediaAdopting = false; } },
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
    async addSavedAnnotation(item) {
      if (!this.active || this.sending || this.historyLoading || this.actionBusy || this.annotationAdding || this.attachmentRows.length >= 10) return;
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
      if (scopeKey !== this.attachmentScope || this.sending || !this.active || this.actionBusy || !row?.attachment?.id || (row.attachment.conversation_id && row.attachment.conversation_id !== this.selected.conversation_id)) return;
      this.saveAnnotation({ scopeKey, row });
      if (this.attachmentRows.some(item => item.attachment?.id === row.attachment.id)) return;
      if (this.attachmentRows.length >= 10) { this.error = this.$t('attachments.count'); return; }
      this.attachmentRows = [...this.attachmentRows,row];
      return true;
    },
    senderKey,
    revokeStaff() {
      this.selectionController?.abort();
      ++this.staffEpoch; ++this.version; ++this.historyFetchVersion; this.historyLoading = false; this.historyPolling = false;
      clearInterval(this.heartbeat); clearInterval(this.poll); this.heartbeat = null; this.poll = null;
      this.annotationRecords = {}; this.annotationVisible = false; this.attachmentDrafts = {}; ++this.annotationHistoryEpoch; this.annotationHistoryLoading = false; this.annotationHistoryMore = false; this.annotationHistoryError = ''; this.annotationAdding = '';
      this.profile = null; this.accepting = false; this.queue = []; this.assigned = []; this.selected = null; this.messages = [];
      this.loading = false; this.sending = false;
    },
    async verifyStaffAccess(error) {
      if (error.status !== 403 || this.disposed || !this.profile) return;
      // A lost assignment is a resource-level 403; only /me determines whether
      // the current account has lost its entire staff profile.
      if (!this.permissionCheck) {
        const epoch = this.staffEpoch;
        this.permissionCheck = (async () => {
          try {
            const profile = await supportApi.me();
            if (this.disposed || epoch !== this.staffEpoch) return;
            if (!profile) this.revokeStaff();
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
      if (this.presenceBusy || !this.profile || this.disposed) return;
      this.presenceBusy = true; const epoch = this.staffEpoch;
      try { const profile = await supportApi.presence(value); if (!this.disposed && epoch === this.staffEpoch) { this.profile = profile; this.accepting = profile.accepting; } }
      catch (error) { this.error = error.message; this.accepting = false; await this.verifyStaffAccess(error); }
      finally { this.presenceBusy = false; if (this.disposed) supportApi.presence(false).catch(() => {}); }
    },
    async refresh() {
      if (this.refreshBusy || this.disposed) return; this.refreshBusy = true; const refreshEpoch = this.staffEpoch;
      try {
        if (this.profile) {
          const epoch = this.staffEpoch;
          let queue, assigned;
          try { [queue, assigned] = await Promise.all([this.listAll(cursor => supportApi.queue(cursor)), this.listAll(cursor => supportApi.assigned(cursor))]); }
          catch (error) { await this.verifyStaffAccess(error); throw error; }
          if (this.disposed || epoch !== this.staffEpoch) return; this.queue = queue; this.assigned = assigned;
          if (this.selected && !this.sending && !this.historyLoading && !this.actionBusy) {
            const request = assigned.find(item => item.request_id === this.selected.request_id);
            if (request) { this.selected = request; await this.loadHistory(false); }
            else if (this.active) this.selected = { ...this.selected, status: 'RELEASED' };
          }
          if (!this.selected && !this.mediaAdopting && !this.historyLoading && !this.actionBusy) {
            const requestId = this.$route?.query?.request;
            const owned = typeof requestId === 'string' && assigned.find(item => item.request_id === requestId);
            if (owned) await this.selectRequest(owned);
          }
        }
        if (this.admin) { const agents = await this.listAll(cursor => supportApi.agents(cursor)); if (!this.disposed && refreshEpoch === this.staffEpoch) this.agents = agents; }
      } catch (error) { if (!this.disposed) this.error = error.message; }
      finally { this.refreshBusy = false; }
    },
    async selectRequest(request, force = false) { if (!this.profile || this.sending || this.historyLoading || (this.actionBusy && !force)) return; this.selectionController?.abort(); this.selectionController = new AbortController(); this.selected = request; this.messages = []; ++this.annotationHistoryEpoch; this.annotationHistoryLoading = false; this.annotationHistoryMore = false; this.annotationHistoryCursor = '0'; this.annotationHistoryError = ''; this.annotationAdding = ''; ++this.historyFetchVersion; this.historyPolling = false; const version = ++this.version;
      if (this.$router && this.$route?.query?.request !== request.request_id) this.$router.replace({ path: this.$route.path, query: { ...this.$route.query, request: request.request_id } }).catch(() => {});
      const title = conversationApi.detail(request.conversation_id).then(detail => { if (!this.disposed && version === this.version) this.titles[request.conversation_id] = detail.title; }).catch(error => this.verifyStaffAccess(error));
      await Promise.all([this.loadHistory(true), this.loadAnnotations(false), title]); },
    async loadHistory(reset) {
      if (!this.selected || this.historyLoading || (!reset && this.historyPolling)) return;
      const version = this.version, request = this.selected, fetchVersion = ++this.historyFetchVersion;
      if (reset) this.historyLoading = true; else this.historyPolling = true;
      try {
        const after = reset ? '0' : (this.messages.at(-1)?.seq || '0');
        const page = await allPages(cursor => conversationApi.messages(request.conversation_id, cursor), after);
        if (version !== this.version || this.disposed) return;
        for (const message of page.items) if (!this.messages.some(item => item.id === message.id)) this.messages.push(message);
      } catch (error) { if (version === this.version) this.error = error.message; await this.verifyStaffAccess(error); }
      finally { if (fetchVersion === this.historyFetchVersion) { if (reset) this.historyLoading = false; else this.historyPolling = false; } }
    },
    async claim(request) {
      if (this.actionBusy || this.historyLoading || !this.profile || !this.accepting) return; this.actionBusy = true; this.error = ''; const epoch = this.staffEpoch;
      try { const claimed = await supportApi.action(request.request_id, 'claim'); if (this.disposed || epoch !== this.staffEpoch) return; await this.selectRequest(claimed, true); this.$refs.expertLayout?.closeClients(); await this.refresh(); }
      catch (error) { this.error = error.message; await this.verifyStaffAccess(error); } finally { this.actionBusy = false; }
    },
    async end(action) {
      if (!this.active || this.actionBusy || this.sending) return; this.actionBusy = true; const epoch = this.staffEpoch;
      try { const selected = await supportApi.action(this.selected.request_id, action); if (this.disposed || epoch !== this.staffEpoch) return; this.selected = selected; await this.refresh(); }
      catch (error) { this.error = error.message; await this.verifyStaffAccess(error); } finally { this.actionBusy = false; }
    },
    composerKeydown(event) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); this.send(); } },
    appendSuggestion(suggestion) {
      if (!this.active || this.sending || this.disposed || suggestion?.conversation_id !== this.selected?.conversation_id || typeof suggestion.text !== 'string' || !suggestion.text.trim()) return;
      const next = [this.draft, suggestion.text.trim()].filter(Boolean).join('\n\n');
      if (next.length > 8000) { this.error = '回复草稿最多8000字，请先整理现有内容后再加入建议。'; return; }
      this.draft = next;
    },
    async send() {
      if (!this.active || this.sending || (!this.draft.trim() && !this.attachmentRows.length) || !this.attachmentsReady || this.historyLoading) return;
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
    async saveProfile() {
      if (this.saving || !this.admin || !/^\d+$/.test(this.edit.user_id)) return; this.saving = true;
      try { const { user_id, ...payload } = this.edit; await supportApi.profile(user_id, payload); await this.refresh(); }
      catch (error) { this.error = error.message; } finally { this.saving = false; }
    },
  },
};
</script>
<style scoped>
.support-workspace { padding:0; width:100%; margin:0; text-align:left; color:#173b37; }.workspace-heading { display:flex; align-items:center; justify-content:space-between; gap:20px; margin:0 0 18px; }.eyebrow { font-size:11px; color:#6c817c; letter-spacing:1px; }h1 { font-size:24px; margin:4px 0 0; font-weight:650; }.presence { display:flex; gap:14px; align-items:center; flex-wrap:wrap; }.presence strong { font-size:13px; }.presence-state { display:flex; align-items:center; gap:6px; font-size:12px; color:#768681; }.presence-state i { width:7px; height:7px; border-radius:50%; background:#a0aaa6; }.presence-state.working { color:#087f7b; }.presence-state.working i { background:#0b9d78; }.conversation-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:18px 20px; background:white; border-bottom:1px solid #dce7e3; flex:none; }.conversation-heading h2 { font-size:19px; margin:0 0 6px; overflow-wrap:anywhere; }.conversation-heading p { font-size:12px; margin:0; color:#6c817c; line-height:1.7; }.request-id { overflow-wrap:anywhere; font-size:10px; }.status-dot { display:inline-block; width:6px; height:6px; border-radius:50%; background:#087f7b; margin-right:6px; }.session-actions { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:6px; flex:none; }.session-actions .el-button { margin:0; font-size:11px; padding:8px; height:auto; }.media-region { position:relative; min-height:340px; height:auto; max-height:none; flex:0 0 auto; background:#153833; overflow:visible; isolation:isolate; }.media-region.annotation-visible { min-height:420px; }.chat { display:flex; flex:1; flex-direction:column; min-height:340px; background:white; }.chat-heading { display:flex; gap:8px; align-items:center; padding:12px 18px; border-bottom:1px solid #e5ede9; }.chat-heading h3 { margin:0; font-size:14px; }.chat-heading small { color:#8a9b94; }.history { min-height:80px; max-height:280px; flex:1; overflow:auto; padding:10px 18px; overscroll-behavior:contain; }article { margin:0 0 14px; }article>small { display:block; font-size:10px; color:#7b8c85; margin-bottom:5px; }.message-content { display:inline-block; padding:9px 12px; max-width:95%; background:#f2f5f3; border:1px solid #e8eeea; border-radius:0 9px 9px 9px; }.message-content p { white-space:pre-wrap; overflow-wrap:anywhere; font-size:13px; line-height:1.7; margin:0; }.staff-message { text-align:right; }.staff-message .message-content { background:#e2f4ed; border-color:#d3e9df; border-radius:9px 0 9px 9px; text-align:left; }.chat-empty { font-size:13px; line-height:1.8; color:#7b8c85; padding:18px; text-align:center; margin:0; }.composer { padding:0 16px 12px; border-top:1px solid #e5ede9; }.composer-footer { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-top:9px; }.composer-footer small,.retry-note { font-size:10px; color:#85958d; line-height:1.6; }.retry-note { margin:7px 0 0; }.composer :deep(.el-textarea__inner:focus) { box-shadow:0 0 0 1px #087f7b inset; }.composer :deep(.el-button--primary) { background:#087f7b; border-color:#087f7b; }.tool-section { padding-bottom:18px; margin-bottom:18px; border-bottom:1px solid #e2ebe7; }.tool-section h3 { font-size:15px; margin:0 0 12px; }.context>strong { font-size:13px; }.context p { color:#6c817c; font-size:12px; line-height:1.7; }.context dl { margin:14px 0 0; display:grid; gap:12px; }.context dl>div { display:flex; justify-content:space-between; gap:10px; font-size:11px; }.context dt { flex:none; color:#6c817c; }.context dd { margin:0; text-align:right; overflow-wrap:anywhere; }.admin { margin-top:22px; border:1px solid #e2ebe7; border-radius:10px; padding:18px; background:white; }.admin>summary { cursor:pointer; font-size:14px; }.admin h2 { font-size:16px; }.admin p { font-size:12px; color:#6c817c; }.toolbar { display:flex; gap:10px; align-items:center; flex-wrap:wrap; margin:14px 0; }.toolbar .el-input { width:230px; }.toolbar .el-select { width:180px; }
@media(max-width:1000px) { .workspace-heading { align-items:flex-start; }.presence { gap:8px; justify-content:flex-end; }.presence :deep(.el-switch__label) { display:none; }.conversation-heading { padding:14px; }.session-actions { flex-direction:column; } }
@media(max-width:760px) { .workspace-heading { margin-bottom:12px; gap:10px; }h1 { font-size:20px; }.eyebrow { font-size:10px; }.presence { gap:6px; }.presence strong { display:none; }.presence-state { font-size:11px; }.conversation-heading h2 { font-size:17px; }.conversation-heading { padding:12px; }.request-id { display:none; }.media-region { height:auto; min-height:300px; }.media-region.annotation-visible { min-height:400px; }.history { max-height:250px; }.composer { padding:0 12px 12px; }.composer-footer small { max-width:65%; } }

.support-workspace { display:flex; flex-direction:column; height:100%; min-height:0; }
.workspace-heading { flex:none; margin-bottom:10px; min-height:36px; gap:12px; }
.workspace-heading .eyebrow { display:none; }
h1 { font-size:18px; margin:0; }
.admin { flex:none; margin-top:8px; padding:8px 12px; max-height:40%; overflow:auto; }
.admin>summary { font-size:12px; }
.conversation-heading { padding:12px 16px; }
.conversation-heading h2 { font-size:17px; margin-bottom:4px; }
.media-region.annotation-visible { min-height:0; }
.media-region.annotation-visible :deep(.media-stage-host) { height:min(560px,60dvh); min-height:min(560px,60dvh); max-height:none; flex:none; aspect-ratio:auto; }
.chat { min-height:290px; }
@media(max-width:760px) {
  .workspace-heading { margin-bottom:6px; min-height:34px; }
  h1 { font-size:16px; }
  .presence { gap:5px; }
  .presence-state { font-size:10px; }
  .conversation-heading { padding:10px 12px; }
  .conversation-heading h2 { font-size:15px; }
  .media-region.annotation-visible :deep(.media-stage-host) { height:min(560px,62dvh); min-height:min(560px,62dvh); }
  .admin { padding:6px 10px; margin-top:6px; }
}
</style>
