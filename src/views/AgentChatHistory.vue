<template>
  <div class="agent-history-page" :class="{ embedded }">
    <header v-if="!embedded" class="history-heading">
      <div><h1>{{ agentName ? `${agentName} · ${$t('home.chatHistory')}` : $t('home.chatHistory') }}</h1><p>{{ $t('chatHistory.readOnly') }}</p></div>
      <el-button @click="$router.push('/home')">{{ $t('chatHistory.back') }}</el-button>
    </header>
    <div v-if="error" class="history-error" role="alert"><span>{{ error }}</span><el-button size="small" @click="refresh">{{ $t('chatHistory.retry') }}</el-button></div>
    <div v-if="agentId" class="history-grid" :class="{ 'history-grid--embedded': embedded }">
      <aside v-if="!embedded" class="history-sidebar" :aria-label="$t('chatHistory.sessions')">
        <div class="history-filters">
          <label for="history-device">{{ $t('chatHistory.deviceFilter') }}</label>
          <select id="history-device" :value="device" @change="changeDevice($event.target.value)">
            <option value="">{{ $t('chatHistory.allDevices') }}</option>
            <option v-for="mac in devices" :key="mac" :value="mac">{{ mac }}</option>
          </select>
          <div class="session-list-heading"><strong>{{ $t('chatHistory.sessions') }}</strong><button type="button" class="history-icon" :aria-label="$t('chatHistory.refresh')" :disabled="loadingSessions" @click="refresh"><LegacyIcon class="el-icon-refresh" /></button></div>
        </div>
        <div class="history-sessions" :aria-busy="loadingSessions">
          <p v-if="loadingSessions && !sessions.length" class="history-empty" role="status">{{ $t('chatHistory.loading') }}</p>
          <p v-else-if="!sessions.length" class="history-empty">{{ $t('chatHistory.emptySessions') }}</p>
          <button v-for="session in sessions" :key="session.sessionId" class="history-session" :class="{ selected: current?.sessionId === session.sessionId }" :aria-current="current?.sessionId === session.sessionId ? 'true' : undefined" @click="selectSession(session)">
            <span class="session-icon"><LegacyIcon class="el-icon-chat-dot-round" /></span>
            <span class="session-description"><strong>{{ session.title || $t('home.chatHistory') }}</strong><small>{{ formatTime(session.createdAt) }}</small><small v-if="session.macAddress">{{ session.macAddress }}</small></span>
            <span class="session-count">{{ session.chatCount || 0 }}</span>
          </button>
          <button v-if="hasMore" class="history-more" :disabled="loadingSessions" @click="loadSessions(true)">{{ $t(loadingSessions ? 'chatHistory.loading' : 'chatHistory.loadMore') }}</button>
        </div>
      </aside>
      <section class="history-detail" :aria-label="$t('home.chatHistory')">
        <header class="history-detail-heading"><div><h2>{{ current?.title || (current ? formatTime(current.createdAt) : $t('chatHistory.selectSession')) }}</h2><p v-if="current">{{ messages.length }} {{ $t('chatHistory.messages') }}<template v-if="device || current.macAddress"> · <MacAddressMask :mac-address="device || current.macAddress" /></template></p></div><div v-if="current" class="history-heading-actions"><button v-if="!embedded" type="button" class="history-search-entry" :disabled="loadingMessages" @click="openHistorySearch"><LegacyIcon class="el-icon-search" />{{ $t('historyFinder.title') }}</button><el-button :disabled="loadingMessages" @click="selectSession(current)">{{ $t('chatHistory.refresh') }}</el-button></div></header>
        <div v-if="historyContextActive" class="history-context-bar"><span>{{ $t('historyFinder.historical') }}</span><button type="button" @click="selectSession(current)">{{ $t('historyFinder.latest') }}</button></div>
        <div ref="messageList" class="history-messages" :aria-busy="loadingMessages" @scroll.passive="messageListScrolled">
          <div v-if="current && !loadingMessages && messages.length" class="message-history-status"><button v-if="messageStart > 0" class="history-more" @click="loadOlderMessages">{{ $t('chatHistory.earlierMessages') }}</button><span v-else>{{ $t('chatHistory.allMessagesLoaded') }}</span></div>
          <p v-if="loadingMessages" class="history-empty" role="status">{{ $t('chatHistory.loading') }}</p>
          <p v-else-if="!current" class="history-empty">{{ $t('chatHistory.selectSession') }}</p>
          <p v-else-if="!messages.length" class="history-empty">{{ $t('chatHistory.noMessages') }}</p>
          <article v-for="(message, index) in messages" v-else :key="`${current.sessionId}-${messageStart + index}`" class="history-message chat-message" :data-history-index="messageStart + index" tabindex="-1" :class="['sender-' + (message.chatType === 1 ? 'user' : message.chatType === 3 ? 'tool' : 'assistant'), { 'from-user': message.chatType === 1, 'from-tool': message.chatType === 3, 'history-target': highlightedIndex === messageStart + index }]">
            <MessageAvatar class="message-avatar thread-avatar" :sender-type="message.chatType === 1 ? 'user' : message.chatType === 3 ? 'tool' : 'assistant'" :label="$t(message.chatType === 1 ? 'chatHistory.user' : message.chatType === 3 ? 'chatHistory.tool' : 'chatHistory.assistant')" />
            <div class="message-body thread-body"><div class="message-meta thread-meta"><strong>{{ $t(message.chatType === 1 ? 'chatHistory.user' : message.chatType === 3 ? 'chatHistory.tool' : 'chatHistory.assistant') }}</strong><time>{{ formatTime(message.createdAt) }}</time></div><div class="message-bubble thread-bubble"><template v-for="(part, partIndex) in contentParts(message.content)" :key="partIndex"><details v-if="part.type === 'tool_result' && part.text.length > 80"><summary>{{ part.text.split('\n')[0].slice(0, 80) }}…</summary><pre>{{ part.text }}</pre></details><p v-else :class="{ 'tool-content': part.type === 'tool' || part.type === 'tool_result' }">{{ part.text }}</p></template></div><button v-if="message.audioId" class="history-audio" :disabled="loadingAudio === messageStart + index" @click="playAudio(message, messageStart + index)"><LegacyIcon :class="playingAudio === messageStart + index ? 'el-icon-video-pause' : 'el-icon-video-play'" /> {{ $t(playingAudio === messageStart + index ? 'chatHistory.audioStop' : 'chatHistory.audioPlay') }}</button></div>
          </article>
        </div>
        <footer class="history-downloads"><el-button :disabled="!current || loadingMessages || !!downloadBusy" :loading="downloadBusy === 'current'" type="primary" plain @click="download(false)">{{ $t('chatHistory.downloadCurrentSession') }}</el-button><el-button :disabled="!current || loadingMessages || !!downloadBusy" :loading="downloadBusy === 'previous'" @click="download(true)">{{ $t('chatHistory.downloadCurrentWithPreviousSessions') }}</el-button></footer>
      </section>
    </div>
    <p v-else class="history-empty">{{ $t('chatHistory.selectAgent') }}</p>
    <HistorySearchDrawer v-if="historyVisible && current" :key="historyScope" :title="current.title" :scope-key="historyScope" :load-page="searchHistoryPage" @close="historyVisible = false" @locate="locateHistoryMessage" />
  </div>
</template>

<script setup>
import MessageAvatar from '../components/MessageAvatar.vue';
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useAuthStore } from '../stores/auth';
import i18n from '../i18n';
import { getServiceUrl } from '../apis/serviceUrl';
import { createAgentHistoryClient } from '../apis/module/agentHistoryClient.mjs';
import { formatBeijingTime } from '../utils/date';
import HistorySearchDrawer from '../components/HistorySearchDrawer.vue';
import { legacyHistoryMessage } from '../utils/historySearch.mjs';
import MacAddressMask from '../components/MacAddressMask.vue';

const props = defineProps({ embedded: Boolean, historyAgentId: String, historyAgentName: String, historySession: Object, historyDevice: { type: String, default: '' } });
const emit = defineEmits(['history-availability']);
const route = useRoute() || { query: {} }, auth = useAuthStore();
const t = key => i18n.t(key);
const value = input => typeof input === 'string' ? input : '';
const agentId = computed(() => props.embedded ? (props.historyAgentId || '') : value(route.query.agentId));
const agentName = computed(() => props.embedded ? (props.historyAgentName || '') : value(route.query.agentName));
const device = ref(''), devices = ref([]), sessions = ref([]), messages = ref([]), current = ref(null);
const loadingSessions = ref(false), loadingMessages = ref(false), hasMore = ref(false), error = ref('');
const playingAudio = ref(null), loadingAudio = ref(null), downloadBusy = ref(''), messageList = ref(null);
const messageStart = ref(0), messageEnd = ref(0), historyVisible = ref(false), historyContextActive = ref(false), highlightedIndex = ref(null);
const historyScope = computed(() => agentId.value + ':' + (current.value?.sessionId || '') + ':' + device.value);
// The legacy endpoint has no message pagination; reveal its cached rows ten at a time.
let sessionMessages = [], lastMessageScrollTop = 0;
const api = createAgentHistoryClient({ baseUrl: getServiceUrl() || '/xiaozhi', getToken: () => auth.bearer, onUnauthorized: () => auth.logout() });
let scope = new AbortController(), selection = new AbortController(), audioRequest = null, audio = null, page = 1, alive = true;
const urls = new Set(), downloadTimers = new Set();
function stopAudio() {
  audioRequest?.abort(); audioRequest = null;
  if (audio) { audio.pause(); audio.removeAttribute('src'); audio.load(); audio = null; }
  playingAudio.value = null; loadingAudio.value = null;
}
function clearSelection() {
  selection.abort(); selection = new AbortController(); stopAudio();
  current.value = null; messages.value = []; loadingMessages.value = false; downloadBusy.value = '';
  sessionMessages = []; messageStart.value = 0; messageEnd.value = 0; lastMessageScrollTop = 0; historyVisible.value = false; historyContextActive.value = false; highlightedIndex.value = null;
}
function isCurrent(controller) { return alive && !controller.signal.aborted; }
function failure(controller, key = 'chatHistory.loadFailed') { if (isCurrent(controller)) error.value = t(key); }
async function loadSessions(more = false) {
  if (!agentId.value || !auth.bearer || loadingSessions.value || (more && !hasMore.value)) return;
  const controller = scope; loadingSessions.value = true;
  try {
    const result = await api.sessions(agentId.value, { page: more ? page : 1, limit: 20, macAddress: device.value }, controller.signal);
    if (!isCurrent(controller)) return;
    const rows = Array.isArray(result?.list) ? result.list : [];
    sessions.value = more ? [...sessions.value, ...rows.filter(row => !sessions.value.some(old => old.sessionId === row.sessionId))] : rows;
    page = more ? page + 1 : 2;
    hasMore.value = result?.total != null ? sessions.value.length < Number(result.total) : rows.length === 20;
    if (!current.value && rows.length) selectSession(rows[0]);
  } catch { failure(controller); }
  finally { if (isCurrent(controller)) loadingSessions.value = false; }
}
async function loadDevices(controller) {
  try { const result = await api.devices(agentId.value, controller.signal); if (isCurrent(controller)) devices.value = Array.isArray(result) ? result : []; }
  catch { failure(controller); }
}
function refresh() {
  scope.abort(); scope = new AbortController(); clearSelection();
  sessions.value = []; page = 1; hasMore.value = false; loadingSessions.value = false; error.value = '';
  if (!agentId.value || !auth.bearer) return;
  if (props.embedded) { if (props.historySession) selectSession(props.historySession); return; }
  loadDevices(scope); loadSessions();
}
function changeDevice(mac) { device.value = mac; refresh(); }
async function selectSession(session) {
  clearSelection(); current.value = session; error.value = ''; loadingMessages.value = true;
  const controller = selection;
  try {
    const result = await api.messages(agentId.value, session.sessionId, device.value, controller.signal);
    if (!isCurrent(controller)) return;
    sessionMessages = Array.isArray(result) ? result : [];
    messageStart.value = Math.max(0, sessionMessages.length - 10); messageEnd.value = sessionMessages.length;
    messages.value = sessionMessages.slice(messageStart.value, messageEnd.value);
  } catch { failure(controller); }
  finally {
    if (isCurrent(controller)) {
      loadingMessages.value = false;
      await nextTick();
      if (isCurrent(controller) && messageList.value) { messageList.value.scrollTop = messageList.value.scrollHeight; lastMessageScrollTop = messageList.value.scrollTop; }
    }
  }
}
function messageListScrolled() {
  const top = messageList.value?.scrollTop || 0, movingUp = top < lastMessageScrollTop;
  lastMessageScrollTop = top;
  if (movingUp && top <= 64) loadOlderMessages();
}
async function loadOlderMessages() {
  if (loadingMessages.value || messageStart.value <= 0) return;
  const controller = selection, list = messageList.value, height = list?.scrollHeight || 0, top = list?.scrollTop || 0;
  messageStart.value = Math.max(0, messageStart.value - 10);
  messages.value = sessionMessages.slice(messageStart.value, messageEnd.value);
  await nextTick();
  if (isCurrent(controller) && list) { list.scrollTop = top + list.scrollHeight - height; lastMessageScrollTop = list.scrollTop; }
}
function openHistorySearch() { if (current.value && !loadingMessages.value && !error.value) historyVisible.value = true; }
defineExpose({ openHistorySearch });
watch([current, loadingMessages, error], () => emit('history-availability', !!current.value && !loadingMessages.value && !error.value), { immediate: true, flush: 'sync' });
async function searchHistoryPage(before, signal) {
  if (signal.aborted || !current.value) throw new DOMException('Aborted', 'AbortError');
  const end = before === '' ? sessionMessages.length : Number(before);
  if (!Number.isSafeInteger(end) || end < 0 || end > sessionMessages.length) throw new Error('INVALID_HISTORY_CURSOR');
  const start = Math.max(0, end - 100);
  return { items: sessionMessages.slice(start, end).map((message, index) => legacyHistoryMessage(message, start + index, current.value.sessionId)).reverse(), next_before: String(start), has_more: start > 0 };
}
async function locateHistoryMessage(message) {
  const index = message.legacyIndex, controller = selection;
  if (!current.value || !Number.isSafeInteger(index) || index < 0 || index >= sessionMessages.length || message.id !== current.value.sessionId + '-' + index) return;
  stopAudio();
  messageStart.value = Math.max(0, index - 4); messageEnd.value = Math.min(sessionMessages.length, index + 6);
  messages.value = sessionMessages.slice(messageStart.value, messageEnd.value);
  historyContextActive.value = true; highlightedIndex.value = index; historyVisible.value = false;
  await nextTick();
  if (!isCurrent(controller)) return;
  const list = messageList.value, target = [...(list?.querySelectorAll('[data-history-index]') || [])].find(row => Number(row.dataset.historyIndex) === index);
  if (list && target) {
    list.scrollTop += target.getBoundingClientRect().top - list.getBoundingClientRect().top - (list.clientHeight - target.clientHeight) / 2;
    lastMessageScrollTop = list.scrollTop; target.focus({ preventScroll: true });
  }
}
function contentParts(content) {
  let parsed = content;
  if (typeof content === 'string') { try { parsed = JSON.parse(content); } catch { /* Plain text is the normal wire format. */ } }
  if (Array.isArray(parsed)) return parsed.filter(part => part && typeof part.text === 'string').map(part => ({ type: part.type, text: part.text }));
  return [{ type: 'text', text: typeof parsed?.content === 'string' ? parsed.content : typeof content === 'string' ? content : '' }];
}
function formatTime(input) {
  return formatBeijingTime(input, i18n.locale.replace('_', '-'), {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  }) || '—';
}
async function playAudio(message, index) {
  if (playingAudio.value === index) { stopAudio(); return; }
  stopAudio(); const controller = new AbortController(); audioRequest = controller; loadingAudio.value = index;
  try {
    const grant = await api.audioGrant(message.audioId, controller.signal);
    if (!isCurrent(controller) || audioRequest !== controller) return;
    const player = new Audio(api.audioUrl(grant)); audio = player; playingAudio.value = index;
    player.onended = () => { if (audio === player) stopAudio(); };
    player.onerror = () => { if (audio === player) { failure(controller, 'chatHistory.audioFailed'); stopAudio(); } };
    await player.play();
  } catch { failure(controller, 'chatHistory.audioFailed'); if (audioRequest === controller) stopAudio(); }
  finally { if (audioRequest === controller) loadingAudio.value = null; }
}
async function download(previous) {
  if (!current.value || downloadBusy.value) return;
  const controller = selection; downloadBusy.value = previous ? 'previous' : 'current'; error.value = '';
  try {
    const blob = await api.download(agentId.value, current.value.sessionId, device.value, previous, controller.signal);
    if (!isCurrent(controller)) return;
    const url = URL.createObjectURL(blob); urls.add(url);
    const link = document.createElement('a'); link.href = url; link.download = previous ? 'history-with-previous.txt' : 'history.txt'; document.body.append(link); link.click(); link.remove();
    const timer = setTimeout(() => { URL.revokeObjectURL(url); urls.delete(url); downloadTimers.delete(timer); }, 10000); downloadTimers.add(timer);
  } catch { failure(controller, 'chatHistory.downloadLinkFailed'); }
  finally { if (isCurrent(controller)) downloadBusy.value = ''; }
}
function visibility() { if (document.hidden) stopAudio(); }
document.addEventListener('visibilitychange', visibility);
watch([agentId, () => auth.bearer, () => props.historySession?.sessionId, () => props.historyDevice], () => { device.value = props.embedded ? props.historyDevice : ''; devices.value = []; refresh(); }, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { alive = false; scope.abort(); clearSelection(); document.removeEventListener('visibilitychange', visibility); downloadTimers.forEach(clearTimeout); urls.forEach(url => URL.revokeObjectURL(url)); });
</script>

<style scoped lang="scss">
@use '../styles/chat-messages' as messages;
.history-heading-actions{display:flex;align-items:center;gap:12px;flex-shrink:0}.history-search-entry{display:flex;align-items:center;gap:6px;border:0;background:transparent;color:var(--el-color-primary);font:inherit;font-size:var(--admin-font-xs);padding:8px 0;white-space:nowrap;cursor:pointer}.history-context-bar{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:10px 24px;background:var(--el-color-primary-light-9);font-size:var(--admin-font-xs);flex-shrink:0}.history-context-bar button{border:0;background:transparent;color:var(--el-color-primary);font:inherit;font-size:var(--admin-font-xs);cursor:pointer}.history-message.history-target .message-bubble{box-shadow:0 0 0 2px var(--el-color-primary-light-5);background:var(--el-color-primary-light-9)}.history-message.history-target:focus{outline:none}
@media(max-width:480px){.history-heading-actions{gap:5px}.history-search-entry{font-size:var(--admin-font-xs)}.history-detail-heading>div:first-child{min-width:0}.history-detail-heading .el-button{padding:7px}}
.history-messages{overflow-anchor:none}.message-history-status{display:flex;align-items:center;justify-content:center;min-height:42px;margin-bottom:16px;color:var(--el-text-color-secondary);font-size:var(--admin-font-xs)}
.agent-history-page.embedded{gap:0;min-height:360px}.history-grid.history-grid--embedded{grid-template-columns:minmax(0,1fr);grid-template-rows:minmax(0,1fr);border:0;border-radius:0}

.agent-history-page{height:100%;min-height:0;display:flex;flex-direction:column;color:var(--admin-text);text-align:left;gap:16px}.history-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-shrink:0}.history-heading h1{margin:0;font-size:var(--admin-font-title);line-height:1.4;overflow-wrap:anywhere}.history-heading p{margin:5px 0 0;font-size:var(--admin-font-base);color:var(--el-text-color-secondary)}.history-heading .el-button{flex-shrink:0}.history-grid{display:grid;grid-template-columns:292px minmax(0,1fr);flex:1;min-height:0;background:var(--el-bg-color);border:1px solid var(--admin-border);border-radius:6px;overflow:hidden}.history-sidebar,.history-detail{min-height:0;min-width:0;display:flex;flex-direction:column}.history-sidebar{border-right:1px solid var(--admin-border);background:var(--el-fill-color-extra-light)}.history-filters{padding:18px 16px 6px;flex-shrink:0}.history-filters label{display:block;font-size:var(--admin-font-xs);color:var(--el-text-color-secondary);margin-bottom:8px}.history-filters select{width:100%;height:36px;padding:0 9px;border:1px solid var(--admin-border);border-radius:5px;background:var(--el-bg-color);color:inherit;font:inherit}.session-list-heading{display:flex;justify-content:space-between;align-items:center;margin-top:13px;font-size:var(--admin-font-base)}.history-icon{border:0;background:transparent;color:var(--el-text-color-secondary);font-size:var(--admin-font-lg);padding:6px;cursor:pointer}.history-sessions{overflow:auto;min-height:0;flex:1;padding:4px 9px 12px}.history-session{width:100%;display:flex;align-items:center;gap:10px;border:1px solid transparent;border-radius:6px;background:transparent;text-align:left;padding:13px 10px;color:inherit;cursor:pointer}.history-session:hover{background:var(--el-fill-color-light)}.history-session.selected{border-color:var(--el-color-primary-light-7);background:var(--el-color-primary-light-9)}.session-icon{font-size:var(--admin-font-title);color:var(--el-color-primary)}.session-description{flex:1;min-width:0;display:grid;gap:5px}.session-description strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:var(--admin-font-base);font-weight:500}.session-description small{font-size:var(--admin-font-xs);color:var(--el-text-color-secondary)}.session-count{font-size:var(--admin-font-xs);align-self:flex-start;color:var(--el-text-color-secondary);background:var(--el-fill-color);border-radius:10px;padding:2px 6px}.history-more{display:block;width:100%;border:0;background:transparent;color:var(--el-color-primary);padding:15px;cursor:pointer}.history-detail-heading{display:flex;align-items:center;justify-content:space-between;padding:18px 24px;gap:12px;border-bottom:1px solid var(--admin-border);flex-shrink:0}.history-detail-heading h2{font-size:var(--admin-font-md);margin:0;overflow-wrap:anywhere}.history-detail-heading p{font-size:var(--admin-font-xs);color:var(--el-text-color-secondary);margin:7px 0 0}.history-detail-heading .el-button{flex-shrink:0}.history-messages{flex:1;min-height:0;overflow:auto;padding:24px;overscroll-behavior:contain}.history-empty{text-align:center;color:var(--el-text-color-secondary);font-size:var(--admin-font-base);padding:28px 12px;line-height:1.8}.history-message{display:flex;gap:12px;margin-bottom:24px}.message-body{max-width:85%;min-width:0}.message-meta{font-size:var(--admin-font-xs);display:flex;align-items:center;gap:12px;color:var(--el-text-color-secondary);margin:0 0 7px}.message-meta strong{font-weight:500;color:var(--admin-text)}.message-meta time{font-size:var(--admin-font-xs)}.message-bubble{padding:12px 15px;border:1px solid var(--el-border-color-lighter);border-radius:0 9px 9px 9px;background:var(--el-fill-color-extra-light);overflow-wrap:anywhere;font-size:var(--admin-font-base);line-height:1.8}.message-bubble p{margin:0;white-space:pre-wrap}.message-bubble p+p{margin-top:8px}.from-user{flex-direction:row-reverse}.from-user .message-meta{justify-content:flex-end}.from-user .message-bubble{background:var(--el-color-primary-light-9);border-color:var(--el-color-primary-light-8);border-radius:9px 0 9px 9px}.from-tool .message-bubble,.tool-content{font-family: var(--admin-font-mono);font-size:var(--admin-font-xs)}.message-bubble pre{white-space:pre-wrap;overflow-wrap:anywhere;margin:8px 0 0;font:inherit}.message-bubble summary{cursor:pointer}.history-audio{border:0;background:transparent;color:var(--el-color-primary);padding:7px 0 0;cursor:pointer;font:inherit;font-size:var(--admin-font-xs)}.history-downloads{padding:14px 24px;border-top:1px solid var(--admin-border);display:flex;flex-wrap:wrap;gap:10px;flex-shrink:0;background:var(--el-bg-color)}.history-downloads .el-button{margin:0;max-width:100%;white-space:normal;height:auto;min-height:34px;line-height:1.5;text-align:left}.history-downloads :deep(.el-button>span){white-space:normal;overflow-wrap:anywhere}.history-error{display:flex;align-items:center;justify-content:space-between;gap:12px;border-radius:5px;padding:10px 14px;background:var(--el-color-danger-light-9);color:var(--el-color-danger);font-size:var(--admin-font-base);flex-shrink:0}@media(max-width:760px){.agent-history-page{gap:10px}.history-heading h1{font-size:var(--admin-font-title)}.history-heading p{font-size:var(--admin-font-xs)}.history-grid{grid-template-columns:minmax(0,1fr);grid-template-rows:180px minmax(0,1fr)}.history-sidebar{border-right:0;border-bottom:1px solid var(--admin-border)}.history-filters{padding:10px 12px 0}.history-filters label{display:inline-block;margin:0 9px 0 0}.history-filters select{display:inline-block;width:calc(100% - 75px);height:32px;font-size:var(--admin-font-xs)}.session-list-heading{margin-top:4px}.history-session{padding:9px 8px}.session-description{gap:3px}.session-description small:last-child{display:none}.history-detail-heading{padding:12px}.history-detail-heading h2{font-size:var(--admin-font-md)}.history-messages{padding:16px 12px}.history-message{gap:8px;margin-bottom:18px}.message-body{max-width:calc(100% - 40px)}.message-meta{gap:6px;flex-wrap:wrap}.history-downloads{padding:10px 12px;gap:6px}.history-downloads .el-button{width:100%;font-size:var(--admin-font-xs)}.history-empty{padding:14px 10px}}
@include messages.thread;
</style>
