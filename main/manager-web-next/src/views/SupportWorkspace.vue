<template>
  <section class="support-workspace">
    <header><h1>{{ $t('support.workspace') }}</h1></header>
    <MediaSessionOverlay v-if="profile" :conversation-id="selected?.conversation_id || ''" :request-id="active ? selected.request_id : ''" :identity="attachmentIdentity" :user-id="mediaUserId" staff :writable="active" @changed="mediaChanged" @frame="openLiveFrame" />
    <AnnotationEditor ref="annotationEditor" :conversation-id="selected?.conversation_id || ''" :scope-key="attachmentScope" :identity="attachmentIdentity" :writable="active" :disabled="sending || actionBusy || historyLoading" @ready="stageAnnotation" />
    <el-alert v-if="error" :title="error" type="error" show-icon @close="error = ''" />
    <p v-if="loading" role="status">{{ $t('support.loading') }}</p>
    <p v-if="!loading && !profile" role="status">{{ admin ? `${$t('support.agent')} · ${$t('knowledgeBaseDialog.statusDisabled')}` : $t('support.denied') }}</p>
    <section v-if="profile">
      <div class="toolbar"><strong>{{ $t('support.agent') }} · {{ agentName }}</strong><el-switch :aria-label="$t('support.accepting')" v-model="accepting" :disabled="presenceBusy" :active-text="$t('support.accepting')" :inactive-text="$t('support.paused')" @change="updatePresence" /><span>{{ $t('support.lease') }}</span><el-button :disabled="refreshBusy" @click="refresh">{{ $t('support.refresh') }}</el-button></div>
      <div class="workspace-grid">
        <aside>
          <h2>{{ $t('support.mine') }}</h2>
          <p v-if="!assigned.length">{{ $t('support.empty') }}</p>
          <el-button v-for="request in assigned" :key="request.request_id" class="request-button" :disabled="sending || historyLoading || actionBusy" @click="selectRequest(request)">{{ titles[request.conversation_id] || $t('support.consultation') }} · {{ formatDate(request.requested_at) }} · {{ $t('support.' + request.status) }}</el-button>
          <h2>{{ $t('support.queue') }}</h2>
          <p v-if="!queue.length">{{ $t('support.empty') }}</p>
          <div v-for="request in queue" :key="request.request_id" class="queue-row"><span :title="request.request_id">{{ $t('support.consultation') }} · {{ formatDate(request.requested_at) }}</span><el-button :disabled="!accepting || actionBusy || historyLoading" @click="claim(request)">{{ $t('support.claim') }}</el-button></div>
        </aside>
        <section class="chat">
          <template v-if="selected">
            <div class="toolbar"><strong>{{ titles[selected.conversation_id] || $t('support.consultation') }} · {{ $t('support.' + selected.status) }}</strong><el-button :disabled="actionBusy || sending || !active" @click="end('resolve')">{{ $t('support.resolve') }}</el-button><el-button :disabled="actionBusy || sending || !active" @click="end('requeue')">{{ $t('support.requeue') }}</el-button></div>
            <p v-if="historyLoading">{{ $t('support.loading') }}</p>
            <SupportSummaryPanel :key="attachmentScope" :conversation-id="selected.conversation_id" :messages="messages" :writable="active" @source="focusSummarySource" />
            <small :title="selected.request_id">{{ $t('support.request') }} · {{ selected.request_id }}</small><div class="history" aria-live="polite"><article v-for="message in messages" :key="message.id" :id="`support-message-${message.id}`"><small>{{ $t('support.' + senderKey(message.sender_type)) }} · {{ formatDate(message.created_at) }}</small><template v-for="(part, index) in message.parts" :key="index"><p v-if="part.type === 'text'">{{ part.text }}</p><AttachmentPart v-else-if="['image', 'video', 'audio', 'file'].includes(part.type) && part.attachment_id" :part="part" :scope-key="attachmentScope" :can-annotate="active && !sending && !historyLoading && !actionBusy" @annotate="openAnnotation" @snapshot="openAnnotationSnapshot" /><p v-else-if="part.type === 'location'">{{ part.latitude }}, {{ part.longitude }} · {{ part.coordinate_system }}</p><p v-else>{{ $t('support.nonText') }}</p></template></article></div>
            <AttachmentComposer :key="attachmentScope" v-model="attachmentRows" :conversation-id="selected.conversation_id" :identity="attachmentIdentity" :disabled="sending || !active || historyLoading" /><form @submit.prevent="send"><el-input v-model="draft" type="textarea" :rows="3" maxlength="8000" :disabled="sending || !active" :aria-label="$t('support.reply')" @keydown="composerKeydown" /><el-button native-type="submit" type="primary" :loading="sending" :disabled="!active || (!draft.trim() && !attachmentRows.length) || !attachmentsReady || historyLoading">{{ $t('support.reply') }}</el-button><p>{{ $t('support.retryNote') }}</p></form>
          </template><p v-else>{{ $t('support.select') }}</p>
        </section>
      </div>
    </section>
    <section v-if="admin" class="admin">
      <h2>{{ $t('support.profiles') }}</h2><p>{{ $t('support.profileNote') }}</p>
      <form class="toolbar" @submit.prevent="saveProfile"><el-input v-model="edit.user_id" :aria-label="$t('support.userId')" :placeholder="$t('support.userId')" /><el-select v-model="edit.role" :aria-label="$t('support.role')"><el-option value="support_agent" :label="$t('support.agent')" /><el-option value="expert" :label="$t('support.expert')" /></el-select><el-switch :aria-label="$t('support.enabled')" v-model="edit.enabled" :active-text="$t('support.enabled')" /><el-input-number v-model="edit.capacity_text" :min="1" :max="20" :aria-label="$t('support.capacity')" /><el-button native-type="submit" type="primary" :loading="saving" :disabled="!/^\d+$/.test(edit.user_id)">{{ $t('support.save') }}</el-button></form>
      <el-table :data="agents"><el-table-column :label="$t('support.username')"><template #default="{ row }">{{ row.username }}<el-tag v-if="row.account_enabled === false" type="danger" size="small">{{ $t('knowledgeBaseDialog.statusDisabled') }}</el-tag></template></el-table-column><el-table-column prop="user_id" :label="$t('support.userId')" /><el-table-column :label="$t('support.role')"><template #default="{ row }">{{ $t(row.role === 'expert' ? 'support.expert' : 'support.agent') }}</template></el-table-column><el-table-column :label="$t('support.enabled')"><template #default="{ row }">{{ $t(row.enabled ? 'knowledgeBaseDialog.statusEnabled' : 'knowledgeBaseDialog.statusDisabled') }}</template></el-table-column><el-table-column prop="capacity_text" :label="$t('support.capacity')" /><el-table-column :label="$t('support.edit')"><template #default="{ row }"><el-button @click="edit = { user_id: row.user_id, role: row.role, enabled: row.enabled, capacity_text: row.capacity_text }">{{ $t('support.edit') }}</el-button></template></el-table-column></el-table>
    </section>
  </section>
</template>
<script>
import SupportSummaryPanel from '../components/SupportSummaryPanel.vue';
import MediaSessionOverlay from '../components/MediaSessionOverlay.vue';
import AnnotationEditor from '../components/AnnotationEditor.vue';
import AttachmentComposer from '../components/AttachmentComposer.vue';
import AttachmentPart from '../components/AttachmentPart.vue';
import { mixedMessageParts, attachmentRowsReady, attachmentIdentity } from '../utils/attachments.mjs';
import supportApi from '../apis/module/support';
import conversationApi from '../apis/module/conversation';
import store from '../store';
import { isSupportAdmin, allPages, senderKey, cursorInteger } from '../utils/support.mjs';
export default {
  name: 'SupportWorkspace',
  components: { SupportSummaryPanel, AttachmentComposer, AttachmentPart, AnnotationEditor, MediaSessionOverlay },
  data: () => ({ attachmentDrafts: {}, mediaAdopting: false, selectionController: null, profile: null, accepting: false, presenceBusy: false, assigned: [], queue: [], agents: [], selected: null, messages: [], drafts: {}, titles: {}, pending: {}, loading: true, error: '', refreshBusy: false, staffEpoch: 0, permissionCheck: null, actionBusy: false, sending: false, saving: false, historyLoading: false, version: 0, disposed: false, heartbeat: null, poll: null, edit: { user_id: '', role: 'support_agent', enabled: true, capacity_text: 1 } }),
  computed: {
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
  watch: { attachmentIdentity() { this.selectionController?.abort(); this.attachmentDrafts = {}; this.drafts = {}; this.pending = {}; this.agents = []; this.titles = {}; this.revokeStaff(); } },
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
    openAnnotation(source) { this.$refs.annotationEditor?.openSource(source); },
    openAnnotationSnapshot(id) { this.$refs.annotationEditor?.openSnapshot(id); },
    stageAnnotation({ scopeKey, row }) {
      if (scopeKey !== this.attachmentScope || this.sending || !this.active || this.actionBusy) return;
      if (this.attachmentRows.some(item => item.attachment?.id === row.attachment.id)) return;
      if (this.attachmentRows.length >= 10) { this.error = this.$t('attachments.count'); return; }
      this.attachmentRows = [...this.attachmentRows,row];
    },
    senderKey,
    revokeStaff() {
      this.selectionController?.abort();
      ++this.staffEpoch; ++this.version;
      clearInterval(this.heartbeat); clearInterval(this.poll); this.heartbeat = null; this.poll = null;
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
        }
        if (this.admin) { const agents = await this.listAll(cursor => supportApi.agents(cursor)); if (!this.disposed && refreshEpoch === this.staffEpoch) this.agents = agents; }
      } catch (error) { if (!this.disposed) this.error = error.message; }
      finally { this.refreshBusy = false; }
    },
    async selectRequest(request, force = false) { if (!this.profile || this.sending || this.historyLoading || (this.actionBusy && !force)) return; this.selectionController?.abort(); this.selectionController = new AbortController(); this.selected = request; this.messages = []; const version = ++this.version;
      const title = conversationApi.detail(request.conversation_id).then(detail => { if (!this.disposed && version === this.version) this.titles[request.conversation_id] = detail.title; }).catch(error => this.verifyStaffAccess(error));
      await this.loadHistory(true); await title; },
    async loadHistory(reset) {
      if (!this.selected || this.historyLoading) return;
      const version = this.version, request = this.selected; this.historyLoading = true;
      try {
        const after = reset ? '0' : (this.messages.at(-1)?.seq || '0');
        const page = await allPages(cursor => conversationApi.messages(request.conversation_id, cursor), after);
        if (version !== this.version || this.disposed) return;
        for (const message of page.items) if (!this.messages.some(item => item.id === message.id)) this.messages.push(message);
      } catch (error) { if (version === this.version) this.error = error.message; await this.verifyStaffAccess(error); }
      finally { this.historyLoading = false; }
    },
    async claim(request) {
      if (this.actionBusy || this.historyLoading || !this.profile || !this.accepting) return; this.actionBusy = true; this.error = ''; const epoch = this.staffEpoch;
      try { const claimed = await supportApi.action(request.request_id, 'claim'); if (this.disposed || epoch !== this.staffEpoch) return; await this.selectRequest(claimed, true); await this.refresh(); }
      catch (error) { this.error = error.message; await this.verifyStaffAccess(error); } finally { this.actionBusy = false; }
    },
    async end(action) {
      if (!this.active || this.actionBusy || this.sending) return; this.actionBusy = true; const epoch = this.staffEpoch;
      try { const selected = await supportApi.action(this.selected.request_id, action); if (this.disposed || epoch !== this.staffEpoch) return; this.selected = selected; await this.refresh(); }
      catch (error) { this.error = error.message; await this.verifyStaffAccess(error); } finally { this.actionBusy = false; }
    },
    composerKeydown(event) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); this.send(); } },
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
.support-workspace { padding: 0; width: 100%; margin: 0; text-align: left; color: #344968; }
header,.toolbar { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; margin-bottom: 18px; } header { justify-content: space-between; } h1 { font-size: 22px; } h2 { font-size: 16px; } .toolbar .el-input { width: 230px; } .toolbar .el-select { width: 180px; } .workspace-grid { display: grid; grid-template-columns: 330px minmax(0,1fr); gap: 24px; } aside,.chat,.admin { min-width: 0; box-sizing: border-box; border: 1px solid #e6ebf3; padding: 18px; border-radius: 10px; background: white; } .queue-row { display: flex; gap: 8px; align-items: center; overflow-wrap: anywhere; margin: 8px 0; font-size: 12px; }.request-button { display: block; height: auto; white-space: normal; overflow-wrap: anywhere; text-align: left; margin: 8px 0; max-width: 100%; overflow: hidden; } .history { min-height: 200px; max-height: 55vh; overflow: auto; } article { border-bottom: 1px solid #edf1f5; padding: 12px; } article p { white-space: pre-wrap; overflow-wrap: anywhere; } small { color: #7b8ca5; } form p,.toolbar span { font-size: 12px; color: #7b8ca5; } form .el-button { margin-top: 8px; } .admin { margin-top: 24px; } @media(max-width:760px) { .workspace-grid { grid-template-columns: minmax(0,1fr); } .support-workspace { padding: 12px; } }
</style>
