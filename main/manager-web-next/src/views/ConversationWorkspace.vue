<template>
  <div class="conversation-workspace">
    <header class="workspace-header">
      <div class="workspace-brand"><span class="brand-icon"><LegacyIcon class="el-icon-chat-dot-round" /></span><strong>统一对话</strong><span class="environment-label">{{ environmentLabel }}</span></div>
      <router-link to="/home" class="back-link"><LegacyIcon class="el-icon-back" /> 返回智控台</router-link>
    </header>
    <main class="workspace-grid">
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
          <article v-for="message in messages" :key="message.id" class="message-row"><div class="message-content"><div class="message-meta">我 <span>{{ formatDate(message.created_at, true) }}</span></div><div class="message-bubble"><template v-for="(part, index) in message.parts" :key="index"><p v-if="part.type === 'text'">{{ part.text }}</p><div v-else-if="part.type === 'location'" class="location-card"><LegacyIcon class="el-icon-location-outline" /> 共享位置：{{ part.latitude }}, {{ part.longitude }}<small>{{ part.coordinate_system }} · 精度 {{ part.accuracy_m }} 米</small></div></template></div><div class="message-saved"><LegacyIcon class="el-icon-check" /> 已保存</div></div><span class="sender-avatar">我</span></article>
        </div>
        <footer class="composer-area">
          <div class="capability-note"><LegacyIcon class="el-icon-info" /><span>当前支持文字保存、历史读取与位置展示。AI 回复、附件和人工服务正在接入。</span></div>
          <form class="message-composer" @submit.prevent="sendMessage"><textarea v-model="draft" aria-label="消息内容" :disabled="!selected || sending" maxlength="8000" rows="3" :placeholder="selected ? '描述你的问题…' : '新建或选择会话后，即可开始记录'" @keydown="composerKeydown" /><div class="composer-bottom"><span>{{ draft.length }}/8000 <span class="keyboard-hint">· Enter 发送，Shift + Enter 换行</span></span><button type="submit" class="primary-button send-button" :disabled="!selected || !draft.trim() || sending || loadingMessages"><LegacyIcon :class="sending ? 'el-icon-loading' : 'el-icon-position'" /> {{ sending ? '保存中' : '发送消息' }}</button></div></form>
        </footer>
      </section>
    </main>
    <el-dialog title="新建对话" v-model="createVisible" width="min(440px, 92vw)" :close-on-click-modal="!creating" @opened="focusTitle">
      <label class="title-label" for="conversation-title">对话名称</label><el-input id="conversation-title" ref="titleInput" v-model="newTitle" placeholder="例如：3 号库压缩机运行异响" maxlength="200" :disabled="creating" @keyup.enter="createConversation" /><p class="dialog-hint">使用设备或问题命名，方便以后找到。</p>
      <p v-if="createError" class="create-error" role="alert">{{ createError }}</p>
      <template #footer><el-button :disabled="creating" @click="createVisible = false">取消</el-button><el-button type="primary" :loading="creating" :disabled="!newTitle.trim()" @click="createConversation">创建对话</el-button></template>
    </el-dialog>
  </div>
</template>

<script>
import conversationApi from '../apis/module/conversation';

function cursorInteger(value) {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return BigInt(value);
  if (typeof value === 'string' && /^[0-9]+$/.test(value)) return BigInt(value);
  throw new Error('历史记录游标异常，请刷新后重试');
}


export default {
  name: 'ConversationWorkspace',
  data() {
    return { environmentLabel: import.meta.env.VITE_ENV_LABEL || '开发预览', conversations: [], selected: null, messages: [], drafts: {}, pending: null, error: '', createError: '', loadingList: false, loadingMessages: false, sending: false, createVisible: false, creating: false, newTitle: '', listCursor: 0, listHasMore: false, selectionVersion: 0 };
  },
  computed: {
    draft: {
      get() { return this.selected ? this.drafts[this.selected.id] || '' : ''; },
      set(value) { if (this.selected) this.drafts[this.selected.id] = value; },
    },
  },
  mounted() { this.loadConversations(); },
  beforeUnmount() { this.selectionVersion++; },
  methods: {
    formatDate(value, time = false) { return new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', ...(time ? { hour: '2-digit', minute: '2-digit' } : {}) }); },
    openCreate() { this.newTitle = ''; this.createError = ''; this.createVisible = true; },
    focusTitle() { this.$refs.titleInput?.focus(); },
    async loadConversations(more = false) {
      if (this.loadingList) return;
      this.loadingList = true;
      try {
        const page = await conversationApi.list(more ? this.listCursor : 0);
        this.conversations = more ? [...this.conversations, ...page.items] : page.items;
        this.listCursor = page.next_cursor; this.listHasMore = page.has_more;
        if (!this.selected && page.items.length) await this.selectConversation(page.items[0]);
      } catch (error) { this.error = error.message; }
      finally { this.loadingList = false; }
    },
    async createConversation() {
      if (!this.newTitle.trim() || this.creating) return;
      this.creating = true; this.createError = '';
      try {
        const item = await conversationApi.create(this.newTitle.trim());
        this.createVisible = false;
        await this.loadConversations();
        await this.selectConversation(item);
      } catch (error) { this.createError = error.message; }
      finally { this.creating = false; }
    },
    async selectConversation(item) {
      const version = ++this.selectionVersion;
      this.selected = item; this.messages = []; this.loadingMessages = true; this.error = '';
      try {
        let after = 0; let more = true;
        while (more) {
          const page = await conversationApi.messages(item.id, after);
          if (version !== this.selectionVersion) return;
          this.messages.push(...page.items);
          if (page.has_more && cursorInteger(page.next_cursor) <= cursorInteger(after)) throw new Error('历史记录游标异常，请刷新后重试');
          after = page.next_cursor; more = page.has_more;
        }
        this.scrollToLatest();
      } catch (error) { if (version === this.selectionVersion) this.error = error.message; }
      finally { if (version === this.selectionVersion) this.loadingMessages = false; }
    },
    scrollToLatest() { this.$nextTick(() => { const list = this.$refs.messageList; if (list) list.scrollTop = list.scrollHeight; }); },
    composerKeydown(event) { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) { event.preventDefault(); this.sendMessage(); } },
    async sendMessage() {
      if (!this.selected || !this.draft.trim() || this.sending || this.loadingMessages) return;
      const id = this.selected.id; const text = this.draft;
      const sameRetry = this.pending && this.pending.id === id && this.pending.text === text;
      const clientId = sameRetry ? this.pending.clientId : crypto.randomUUID();
      this.pending = { id, text, clientId }; this.sending = true; this.error = '';
      try {
        const message = await conversationApi.send(id, { client_message_id: clientId, parts: [{ type: 'text', text }] });
        if (!this.messages.some(item => item.id === message.id)) this.messages.push(message);
        if (this.draft === text) this.draft = '';
        this.pending = null; this.scrollToLatest();
      } catch (error) { this.error = `${error.message}。未确认的内容已保留，重试不会重复保存。`; }
      finally { this.sending = false; }
    },
  },
};
</script>

<style scoped>
.conversation-workspace { height: 100vh; height: 100dvh; color: #23334b; background: #f5f7fb; display: flex; flex-direction: column; text-align: left; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', sans-serif; }
.workspace-header { height: 68px; flex: 0 0 68px; background: #fff; border-bottom: 1px solid #e7ebf2; display: flex; align-items: center; justify-content: space-between; padding: 0 30px; gap: 12px; }
.workspace-brand { display: flex; align-items: center; gap: 12px; font-size: 18px; }
.brand-icon { width: 34px; height: 34px; border-radius: 10px; background: #477bfa; color: white; display: grid; place-items: center; font-size: 22px; }
.environment-label { padding: 4px 9px; border-radius: 5px; color: #60759d; background: #f0f4fb; font-size: 12px; font-weight: 500; }
.back-link { color: #67758b; text-decoration: none; font-size: 13px; white-space: nowrap; }
.workspace-grid { display: grid; grid-template-columns: 280px minmax(0, 1fr); flex: 1; min-height: 0; max-width: 1600px; width: 100%; margin: 0 auto; }
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
  .conversation-sidebar { border-right: 0; border-bottom: 1px solid #e6ebf3; padding: 10px 16px; }
  .sidebar-heading { padding: 0 0 5px; }
  .new-conversation { position: absolute; right: 16px; top: 69px; padding: 6px 12px; font-size: 12px; }
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
