<template>
  <div class="conversation-workspace">
    <div class="workspace-grid">
      <aside class="conversation-sidebar" aria-label="会话列表">
        <div class="sidebar-heading"><span>我的会话</span><button class="icon-button" aria-label="刷新会话列表" :disabled="loadingList || sending" @click="loadConversations()"><LegacyIcon class="el-icon-refresh" /></button></div>
        <button class="new-conversation" :disabled="sending" @click="openCreate"><LegacyIcon class="el-icon-plus" /> 新建对话</button>
        <p v-if="loadingList && !conversations.length" class="sidebar-empty" role="status">正在读取会话…</p>
        <p v-else-if="!conversations.length" class="sidebar-empty">还没有会话<br />从一条现场问题开始</p>
        <div class="conversation-list">
          <button v-for="item in conversations" :key="item.id" :disabled="sending" class="conversation-item" :class="{ selected: selected && selected.id === item.id }" :aria-current="selected && selected.id === item.id ? 'true' : null" @click="selectConversation(item)">
            <LegacyIcon class="el-icon-chat-line-square" /><span><strong>{{ item.title }}</strong><small>{{ formatDate(item.created_at) }}</small></span>
          </button>
          <button v-if="listHasMore" class="text-button" :disabled="loadingList" @click="loadConversations(true)">加载更多会话</button>
        </div>
        <div class="sidebar-note"><span class="connection-dot" /> 开发环境独立存储</div>
      </aside>
      <section class="conversation-panel" aria-label="对话内容">
        <div class="panel-heading"><div><h1>{{ selected ? selected.title : '把现场问题留在同一段对话里' }}</h1><p>{{ selected ? '消息保存后，刷新页面仍可继续查看' : '文字记录、位置与后续服务，将在这里衔接' }}</p></div><button v-if="selected" class="icon-button" aria-label="刷新当前对话" :disabled="loadingMessages || sending" @click="selectConversation(selected)"><LegacyIcon class="el-icon-refresh" /></button></div>
        <div v-if="error" class="workspace-error" role="alert"><LegacyIcon class="el-icon-warning-outline" /><span>{{ error }}</span><button aria-label="关闭错误提示" @click="error = ''">×</button></div>
        <div ref="messageList" class="message-list" aria-live="polite" :aria-busy="loadingMessages">
          <div v-if="!selected" class="welcome-state"><span class="welcome-icon"><LegacyIcon class="el-icon-chat-dot-round" /></span><h2>从一个问题开始</h2><p>记录故障现象，保留每一次沟通。<br />选择左侧会话，或新建一段对话。</p><button class="primary-button" @click="openCreate">新建对话 <LegacyIcon class="el-icon-right" /></button></div>
          <div v-else-if="loadingMessages && !messages.length" class="welcome-state" role="status"><LegacyIcon class="el-icon-loading" /><p>正在读取聊天记录…</p></div>
          <div v-else-if="!messages.length" class="welcome-state"><span class="welcome-icon"><LegacyIcon class="el-icon-edit-outline" /></span><h2>这段对话还没有消息</h2><p>可以描述设备、故障表现和已尝试的操作。</p></div>
          <article v-for="message in messages" :key="message.id" class="message-row" :class="{ incoming: message.sender_type && message.sender_type !== 'user' }"><div class="message-content"><div class="message-meta">{{ $t(`support.${senderKey(message.sender_type || 'user')}`) }} <span>{{ formatDate(message.created_at, true) }}</span></div><div class="message-bubble"><template v-for="(part, index) in message.parts" :key="index"><p v-if="part.type === 'text'">{{ part.text }}</p><AttachmentPart v-else-if="['image', 'video', 'audio', 'file'].includes(part.type) && part.attachment_id" :part="part" :scope-key="attachmentScope" :can-annotate="!!selected && selected.state !== 'RESOLVED' && !sending && !loadingMessages" @annotate="openAnnotation" @snapshot="openAnnotationSnapshot" /><div v-else-if="part.type === 'location'" class="location-card"><LegacyIcon class="el-icon-location-outline" /> 共享位置：{{ part.latitude }}, {{ part.longitude }}<small>{{ part.coordinate_system }} · 精度 {{ part.accuracy_m }} 米</small></div></template></div><div v-if="message.sender_type === 'assistant'" class="message-saved">{{ $t('ai.' + (message.status || 'completed')) }} <template v-if="!humanActive && selected?.state === 'AI_ACTIVE' && runFor(message)"><button v-if="['queued', 'running', 'streaming'].includes(runFor(message).status)" :disabled="runBusy[`${selected.id}:${runFor(message).run_id}`]" @click="runAction(runFor(message), 'cancel')">{{ $t('ai.stop') }}</button><button v-if="['failed', 'canceled'].includes(runFor(message).status)" :disabled="runBusy[`${selected.id}:${runFor(message).run_id}`]" @click="runAction(runFor(message), 'retry')">{{ $t('ai.retry') }}</button></template></div><div v-else class="message-saved"><LegacyIcon class="el-icon-check" /> 已保存</div></div><span class="sender-avatar">{{ $t(`support.${senderKey(message.sender_type || 'user')}`) }}</span></article>
        </div>
        <footer class="composer-area">
          <div v-if="selected" class="support-controls" aria-live="polite">
            <span>{{ $t('support.' + (selected.state || 'AI_ACTIVE')) }}</span>
            <span v-if="supportRequest" :title="supportRequest.request_id"> · {{ $t('support.' + supportRequest.status) }}</span>
            <el-button v-if="!humanActive && selected.state !== 'RESOLVED'" :loading="supportBusy" @click="handoff">{{ $t('support.handoff') }}</el-button>
            <el-button v-if="supportRequest?.status === 'queued'" :loading="supportBusy" @click="cancelHandoff">{{ $t('support.cancel') }}</el-button>
            <el-button v-if="selected.state === 'RESOLVED'" :loading="supportBusy" @click="resumeAi">{{ $t('support.resume') }}</el-button>
          </div>
          <div class="capability-note"><LegacyIcon class="el-icon-info" /><span>{{ $t(aiAvailable === false ? 'ai.unavailable' : 'ai.capability') }}</span></div>
          <AnnotationEditor ref="annotationEditor" :conversation-id="selected?.id || ''" :scope-key="attachmentScope" :identity="attachmentIdentity" :writable="!!selected && selected.state !== 'RESOLVED'" :disabled="sending || loadingMessages" @ready="stageAnnotation" />
          <AttachmentComposer :key="attachmentScope" v-model="attachmentRows" :conversation-id="selected?.id || ''" :identity="attachmentIdentity" :disabled="sending || loadingMessages || selected?.state === 'RESOLVED'" />
          <form class="message-composer" @submit.prevent="sendMessage"><textarea v-model="draft" aria-label="消息内容" :disabled="!selected || sending || selected.state === 'RESOLVED'" maxlength="8000" rows="3" :placeholder="selected ? '描述你的问题…' : '新建或选择会话后，即可开始记录'" @keydown="composerKeydown" /><div class="composer-bottom"><span>{{ draft.length }}/8000 <span class="keyboard-hint">· Enter 发送，Shift + Enter 换行</span></span><button type="submit" class="primary-button send-button" :disabled="!selected || (!draft.trim() && !attachmentRows.length) || !attachmentsReady || sending || loadingMessages || selected.state === 'RESOLVED'"><LegacyIcon :class="sending ? 'el-icon-loading' : 'el-icon-position'" /> {{ sending ? '保存中' : '发送消息' }}</button></div></form>
        </footer>
      </section>
    </div>
    <el-dialog title="新建对话" v-model="createVisible" width="min(440px, 92vw)" :close-on-click-modal="!creating" @opened="focusTitle">
      <label class="title-label" for="conversation-title">对话名称</label><el-input id="conversation-title" ref="titleInput" v-model="newTitle" placeholder="例如：3 号库压缩机运行异响" maxlength="200" :disabled="creating" @keyup.enter="createConversation" /><p class="dialog-hint">使用设备或问题命名，方便以后找到。</p>
      <p v-if="createError" class="create-error" role="alert">{{ createError }}</p>
      <template #footer><el-button :disabled="creating" @click="createVisible = false">取消</el-button><el-button type="primary" :loading="creating" :disabled="!newTitle.trim()" @click="createConversation">创建对话</el-button></template>
    </el-dialog>
  </div>
</template>

<script>
import AnnotationEditor from '../components/AnnotationEditor.vue';
import AttachmentComposer from '../components/AttachmentComposer.vue';
import AttachmentPart from '../components/AttachmentPart.vue';
import store from '../store';
import { mixedMessageParts, attachmentRowsReady, attachmentIdentity } from '../utils/attachments.mjs';
import conversationApi from '../apis/module/conversation';
import supportApi from '../apis/module/support';
import { readSse, mergeMessages, projectAiEvent, abortableDelay } from '../utils/aiStream.mjs';
import { senderKey } from '../utils/support.mjs';

function cursorInteger(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return BigInt(value);
  if (typeof value === 'string' && /^[0-9]+$/.test(value)) return BigInt(value);
  throw new Error('历史记录游标异常，请刷新后重试');
}


export default {
  name: 'ConversationWorkspace',
  components: { AttachmentComposer, AttachmentPart, AnnotationEditor },
  data() {
    return { environmentLabel: import.meta.env.VITE_ENV_LABEL || '开发预览', identityEpoch: 0, identityController: new AbortController(), attachmentDrafts: {}, conversations: [], selected: null, messages: [], drafts: {}, pending: {}, error: '', createError: '', loadingList: false, loadingMessages: false, sending: false, createVisible: false, creating: false, newTitle: '', listCursor: 0, listHasMore: false, selectionVersion: 0, supportRequest: null, supportBusy: false, handoffPending: {}, pollTimer: null, messageCursor: '0', eventCursor: '0', pollBusy: false, disposed: false, runs: [], aiAvailable: null, streamController: null, streamCursor: '0', streamProjections: {}, selectionController: null, runBusy: {} };
  },
  computed: {
    attachmentIdentity() { return attachmentIdentity(store.state); },
    attachmentScope() { return `${this.attachmentIdentity}:${this.selected?.id || ''}`; },
    attachmentRows: { get() { return this.attachmentDrafts[this.selected?.id] || []; }, set(value) { if (this.selected) this.attachmentDrafts[this.selected.id] = value; } },
    attachmentsReady() { return attachmentRowsReady(this.attachmentRows); },
    humanActive() { return ['HUMAN_QUEUED', 'HUMAN_ACTIVE'].includes(this.selected?.state); },
    draft: {
      get() { return this.selected ? this.drafts[this.selected.id] || '' : ''; },
      set(value) { if (this.selected) this.drafts[this.selected.id] = value; },
    },
  },
  watch: { attachmentIdentity() { this.resetIdentity(); } },
  mounted() { this.loadConversations(); this.pollTimer = setInterval(() => this.pollConversation(), 4000); },
  beforeUnmount() { this.disposed = true; this.identityController.abort(); this.streamController?.abort(); this.selectionController?.abort(); this.selectionVersion++; clearInterval(this.pollTimer); },
  methods: {
    openAnnotation(source) { this.$refs.annotationEditor?.openSource(source); },
    openAnnotationSnapshot(id) { this.$refs.annotationEditor?.openSnapshot(id); },
    stageAnnotation({ scopeKey, row }) {
      if (scopeKey !== this.attachmentScope || this.sending || !this.selected || this.selected.state === 'RESOLVED') return;
      if (this.attachmentRows.some(item => item.attachment?.id === row.attachment.id)) return;
      if (this.attachmentRows.length >= 10) { this.error = this.$t('attachments.count'); return; }
      this.attachmentRows = [...this.attachmentRows,row];
    },
    senderKey,
    resetIdentity() {
      ++this.identityEpoch; ++this.selectionVersion; this.identityController.abort(); this.identityController = new AbortController();
      this.selectionController?.abort(); this.streamController?.abort();
      this.attachmentDrafts = {}; this.drafts = {}; this.pending = {}; this.conversations = []; this.selected = null; this.messages = []; this.supportRequest = null; this.runs = [];
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
      if (!this.selected || this.pollBusy || this.loadingMessages || this.sending || this.supportBusy || this.disposed) return;
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
    handoff() {
      return this.supportAction(async id => {
        const clientId = this.handoffPending[id] || (this.handoffPending[id] = crypto.randomUUID());
        const result = await conversationApi.handoff(id, { client_request_id: clientId, requested_mode: 'text', target_agent_user_id: null });
        delete this.handoffPending[id];
        if (this.selected?.id === id) this.supportRequest = result;
      });
    },
    cancelHandoff() { const id = this.supportRequest?.request_id; return id && this.supportAction(() => supportApi.action(id, 'cancel')); },
    resumeAi() { return this.supportAction(id => conversationApi.resume(id)); },
    formatDate(value, time = false) { if (!value) return ''; return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', ...(time ? { hour: '2-digit', minute: '2-digit' } : {}) }); },
    openCreate() { this.newTitle = ''; this.createError = ''; this.createVisible = true; },
    focusTitle() { this.$refs.titleInput?.focus(); },
    async loadConversations(more = false) {
      if (this.loadingList) return;
      this.loadingList = true; const epoch = this.identityEpoch;
      try {
        const page = await conversationApi.list(more ? this.listCursor : 0, this.identityController.signal);
        if (this.disposed || epoch !== this.identityEpoch) return;
        this.conversations = more ? [...this.conversations, ...page.items] : page.items;
        this.listCursor = page.next_cursor; this.listHasMore = page.has_more;
        if (!this.selected && page.items.length) await this.selectConversation(page.items[0]);
      } catch (error) { if (!this.disposed && epoch === this.identityEpoch && error.name !== 'AbortError') this.error = error.message; }
      finally { if (!this.disposed && epoch === this.identityEpoch) this.loadingList = false; }
    },
    async createConversation() {
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
      this.streamController?.abort(); this.selectionController?.abort();
      this.selectionController = new AbortController();
      const version = ++this.selectionVersion;
      this.runs = []; this.aiAvailable = null; this.streamCursor = '0'; this.streamProjections = {}; this.pollBusy = false; this.sending = false; this.supportBusy = false;
      this.selected = item; this.messages = []; this.supportRequest = null; this.messageCursor = '0'; this.eventCursor = '0'; this.loadingMessages = true; this.error = '';
      try {
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
    scrollToLatest() { this.$nextTick(() => { const list = this.$refs.messageList; if (list) list.scrollTop = list.scrollHeight; }); },
    composerKeydown(event) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); this.sendMessage(); } },
    async sendMessage() {
      if (!this.selected || this.selected.state === 'RESOLVED' || (!this.draft.trim() && !this.attachmentRows.length) || !this.attachmentsReady || this.sending || this.loadingMessages) return;
      const id = this.selected.id, version = this.selectionVersion; const text = this.draft, rows = [...this.attachmentRows], parts = mixedMessageParts(text, rows), signature = JSON.stringify(parts);
      const previous = this.pending[id];
      const clientId = previous?.signature === signature ? previous.clientId : crypto.randomUUID();
      this.pending[id] = { id, text, signature, clientId }; this.sending = true; this.error = '';
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

<style scoped>
.support-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; font-size: 12px; padding: 8px 0; }
.message-row.incoming { justify-content: flex-end; flex-direction: row-reverse; }
.message-row.incoming .message-meta { text-align: left; }
.message-row.incoming .message-bubble { background: #f2f5f7; border-radius: 2px 12px 12px; }
.message-row.incoming .message-saved { text-align: left; }
.conversation-workspace { height: 100%; min-height: 0; color: #23334b; background: #f5f7fb; display: flex; flex-direction: column; text-align: left; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif; border: 1px solid #e5e9f0; border-radius: 6px; overflow: hidden; }
.workspace-header { height: 68px; flex: 0 0 68px; background: #fff; border-bottom: 1px solid #e7ebf2; display: flex; align-items: center; justify-content: space-between; padding: 0 30px; gap: 12px; }
.workspace-brand { display: flex; align-items: center; gap: 12px; font-size: 18px; }
.brand-icon { width: 34px; height: 34px; border-radius: 10px; background: #477bfa; color: white; display: grid; place-items: center; font-size: 22px; }
.environment-label { padding: 4px 9px; border-radius: 5px; color: #60759d; background: #f0f4fb; font-size: 12px; font-weight: 500; }
.back-link { color: #67758b; text-decoration: none; font-size: 13px; white-space: nowrap; }
.workspace-grid { display: grid; grid-template-columns: 250px minmax(0, 1fr); flex: 1; min-height: 0; width: 100%; margin: 0 auto; }
.conversation-sidebar { display: flex; flex-direction: column; min-height: 0; padding: 26px 18px 16px; border-right: 1px solid #e6ebf3; }
.sidebar-heading { display: flex; justify-content: space-between; align-items: center; font-size: 13px; font-weight: 600; padding: 0 10px 15px; color: #67758b; }
button { font: inherit; cursor: pointer; transition: background .15s; }
button:disabled { cursor: not-allowed; opacity: .5; }
button:focus-visible, a:focus-visible, textarea:focus-visible { outline: 3px solid #91b2ff; outline-offset: 3px; }
.icon-button { width: 30px; height: 30px; border: 0; color: #67758b; border-radius: 6px; background: transparent; font-size: 18px; }
.icon-button:hover { background: #e8edf6; }
.new-conversation { flex-shrink: 0; border: 1px solid #dce5f6; border-radius: 9px; color: #3d70e8; background: #fff; padding: 12px; font-size: 14px; text-align: left; }
.new-conversation i { margin: 0 10px 0 5px; }
.conversation-list { overflow-y: auto; padding-top: 20px; flex: 1; }
.conversation-item { display: flex; align-items: flex-start; gap: 11px; width: 100%; border: 0; border-radius: 9px; background: transparent; color: #66748a; text-align: left; padding: 14px 12px; margin-bottom: 5px; }
.conversation-item.selected { background: #e8effe; color: #346bdc; }
.conversation-item:hover { background: #eaf0fa; }
.conversation-item > i { font-size: 18px; margin-top: 1px; }
.conversation-item > span { min-width: 0; }
.conversation-item strong { display: block; font-weight: 500; font-size: 14px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.conversation-item small { display: block; margin-top: 7px; color: #8b97a9; font-size: 11px; }
.sidebar-empty { color: #8b97a9; font-size: 13px; line-height: 1.9; padding: 26px 10px; }
.sidebar-note { color: #8b97a9; font-size: 11px; display: flex; align-items: center; gap: 7px; padding: 15px 10px 0; }
.connection-dot { width: 6px; height: 6px; border-radius: 50%; background: #62ad91; }
.conversation-panel { display: flex; flex-direction: column; min-width: 0; min-height: 0; background: #fff; }
.panel-heading { padding: 25px 34px 23px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #f0f2f6; gap: 15px; }
h1 { font-size: 17px; line-height: 1.5; margin: 0; font-weight: 600; overflow-wrap: anywhere; }
.panel-heading p { margin: 6px 0 0; color: #8a96a8; font-size: 12px; }
.message-list { flex: 1; min-height: 0; overflow-y: auto; padding: 24px 40px; }
.welcome-state { min-height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center; box-sizing: border-box; padding: 25px; }
.welcome-icon { display: grid; place-items: center; width: 68px; height: 68px; color: #7594d4; background: #f0f5ff; border-radius: 20px; font-size: 33px; margin-bottom: 8px; }
.welcome-state h2 { font-size: 20px; font-weight: 500; margin: 18px 0 9px; }
.welcome-state p { font-size: 13px; line-height: 1.9; color: #8b97a9; margin: 0 0 24px; }
.primary-button { padding: 11px 18px; border: 0; border-radius: 8px; background: #477bfa; color: #fff; font-size: 13px; }
.primary-button:hover:not(:disabled) { background: #356be7; }
.primary-button i { margin: 0 4px; }
.message-row { display: flex; justify-content: flex-end; gap: 12px; margin: 0 auto 26px; max-width: 940px; }
.message-content { max-width: 78%; min-width: 0; }
.message-meta { text-align: right; color: #6e7a8c; font-size: 12px; margin-bottom: 9px; }
.message-meta span { color: #9aa4b3; font-size: 11px; margin-left: 9px; }
.message-bubble { background: #edf3ff; border-radius: 12px 2px 12px 12px; padding: 13px 18px; font-size: 14px; line-height: 1.8; color: #344968; }
.message-bubble p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.message-saved { color: #9aa4b3; font-size: 11px; text-align: right; margin-top: 7px; }
.sender-avatar { flex: 0 0 32px; height: 32px; border-radius: 10px; background: #e6edf9; color: #6c84b1; display: grid; place-items: center; font-size: 12px; margin-top: 23px; }
.location-card small { display: block; margin-top: 5px; color: #7b8ca5; }
.composer-area { padding: 0 34px 24px; }
.capability-note { display: flex; gap: 7px; align-items: baseline; color: #8a96a8; font-size: 11px; line-height: 1.7; padding: 10px 2px; }
.message-composer { border: 1px solid #dde5f2; border-radius: 12px; box-shadow: 0 4px 16px #293b5c06; padding: 14px 16px 12px; }
.message-composer:focus-within { border-color: #83a5f8; }
textarea { display: block; border: 0; background: transparent; width: 100%; resize: none; font: inherit; font-size: 14px; line-height: 1.7; color: #344968; padding: 0; box-sizing: border-box; }
textarea:focus { outline: none; }
textarea::placeholder { color: #a1aabc; }
.composer-bottom { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 8px; }
.composer-bottom > span { font-size: 11px; color: #97a3b5; }
.workspace-error { background: #fff6ee; color: #a36624; margin: 14px 34px 0; padding: 10px 12px; border-radius: 7px; display: flex; gap: 8px; font-size: 12px; line-height: 1.6; }
.workspace-error span { flex: 1; }
.workspace-error button { background: transparent; border: 0; color: inherit; }
.text-button { border: 0; background: transparent; color: #477bfa; font-size: 12px; padding: 12px; }
.title-label { display: block; color: #67758b; font-size: 13px; margin-bottom: 10px; }
.dialog-hint { color: #97a3b5; font-size: 12px; }
.create-error { color: #c04e40; font-size: 12px; }
@media (max-width: 760px) {
  .workspace-header { padding: 0 16px; height: 58px; flex-basis: 58px; }
  .workspace-brand { gap: 8px; font-size: 16px; }
  .brand-icon { width: 28px; height: 28px; font-size: 19px; }
  .workspace-grid { grid-template-columns: minmax(0, 1fr); grid-template-rows: 160px minmax(0, 1fr); }
  .conversation-sidebar { position: relative; border-right: 0; border-bottom: 1px solid #e6ebf3; padding: 10px 16px; }
  .sidebar-heading { padding: 0 0 5px; }
  .new-conversation { position: absolute; right: 16px; top: 9px; padding: 6px 12px; font-size: 12px; }
  .conversation-list { display: flex; gap: 8px; overflow-x: auto; overflow-y: hidden; padding-top: 4px; }
  .conversation-item { width: 190px; min-width: 190px; padding: 9px 10px; }
  .sidebar-note { padding: 5px 0 0; }
  .sidebar-empty { padding: 0; margin: 0; }
  .panel-heading { padding: 13px 18px; }
  h1 { font-size: 15px; }
  .panel-heading p { font-size: 11px; }
  .message-list { padding: 18px 15px; }
  .message-content { max-width: 85%; }
  .composer-area { padding: 0 14px 14px; }
  .keyboard-hint { display: none; }
  .workspace-error { margin: 10px 14px 0; }
  .welcome-state { padding: 10px; }
  .welcome-state h2 { font-size: 17px; margin-top: 12px; }
  .welcome-state p { font-size: 12px; margin-bottom: 12px; }
  .welcome-icon { width: 45px; height: 45px; font-size: 24px; border-radius: 14px; }
}
</style>
