<template>
  <el-drawer :model-value="true" :title="$t('historyFinder.title')" direction="rtl" size="440px" class="history-search-drawer" append-to-body destroy-on-close @update:model-value="value => { if (!value) $emit('close'); }" @opened="keywordInput?.focus()">
    <div class="history-finder">
      <div class="finder-scope"><strong>{{ title }}</strong><span>{{ $t('historyFinder.timezone') }}</span></div>
      <form class="finder-search" @submit.prevent="submitSearch">
        <Search aria-hidden="true" /><input ref="keywordInput" v-model="keyword" type="search" :aria-label="$t('historyFinder.keyword')" :placeholder="$t('historyFinder.keyword')" maxlength="200" /><button type="submit">{{ $t('historyFinder.search') }}</button>
      </form>
      <div class="finder-types" role="group" :aria-label="$t('historyFinder.type')">
        <button v-for="item in types" :key="item.value" type="button" :aria-pressed="type === item.value" @click="type = item.value"><component :is="item.icon" aria-hidden="true" /><span>{{ $t(item.label) }}</span></button>
      </div>
      <div class="finder-date"><label :for="dateId"><Calendar aria-hidden="true" />{{ $t('historyFinder.date') }}</label><input :id="dateId" v-model="date" type="date" :aria-label="$t('historyFinder.date')" /><button v-if="date" type="button" @click="date = ''">{{ $t('historyFinder.clearDate') }}</button></div>
      <div class="finder-results-heading" role="status"><strong>{{ $t('historyFinder.shown', { count: results.length }) }}</strong><span v-if="loading">{{ $t('historyFinder.searching') }}</span></div>
      <div class="finder-results" :aria-busy="loading">
        <div v-if="error" class="finder-error" role="alert"><p>{{ $t('historyFinder.loadFailed') }}</p><button type="button" @click="search(false)">{{ $t('chatHistory.retry') }}</button></div>
        <div v-if="!results.length" class="finder-empty"><Search aria-hidden="true" /><p>{{ $t(loading ? 'historyFinder.searching' : error ? 'historyFinder.loadFailed' : 'historyFinder.empty') }}</p></div>
        <article v-for="message in results" :key="message.id" class="finder-record">
          <button type="button" class="finder-record-main" :disabled="locating" @click="$emit('locate', message)">
            <span class="finder-record-meta"><strong>{{ $t(senderLabel(message.sender_type)) }}</strong><time>{{ formatTime(message.created_at) }}</time></span>
            <span v-if="historyText(message)" class="finder-record-text"><template v-for="(part, index) in highlightedHistoryText(historyExcerpt(message, keyword), keyword)" :key="index"><mark v-if="part.match">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template></span>
            <span v-else class="finder-record-text">{{ $t(mediaKinds(message).length ? 'historyFinder.attachmentMessage' : message.status === 'canceled' ? 'ai.canceled' : 'historyFinder.noText') }}</span>
            <span class="finder-record-bottom"><span class="finder-record-badges"><span v-for="kind in mediaKinds(message)" :key="kind">{{ $t('attachments.' + kind) }}</span></span><span class="finder-locate">{{ $t('historyFinder.context') }}<ArrowRight aria-hidden="true" /></span></span>
          </button>
          <details v-if="attachments(message).length" class="finder-record-attachments"><summary>{{ $t('historyFinder.attachments') }} ({{ attachments(message).length }})</summary><AttachmentPart v-for="(part, index) in attachments(message)" :key="message.id + '-' + index" :part="part" :scope-key="scopeKey + ':history:' + message.id" /></details>
        </article>
        <button v-if="hasMore && results.length" type="button" class="finder-more" :disabled="loading" @click="showMore">{{ $t(loading ? 'chatHistory.loading' : 'historyFinder.more') }}</button>
        <p v-else-if="finished && results.length" class="finder-end">{{ $t('historyFinder.end') }}</p>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref, useId, watch } from 'vue';
import { Search, Calendar, Clock, ChatLineSquare, Picture, VideoCamera, Document, Microphone, ArrowRight } from '@element-plus/icons-vue';
import AttachmentPart from './AttachmentPart.vue';
import i18n from '../i18n';
import { formatBeijingTime } from '../utils/date';
import { matchesHistoryMessage, historyText, historyExcerpt, highlightedHistoryText } from '../utils/historySearch.mjs';

const props = defineProps({ title: String, scopeKey: { type: String, required: true }, loadPage: { type: Function, required: true }, locating: Boolean });
defineEmits(['close', 'locate']);
const dateId = useId(), keywordInput = ref(null);
const keyword = ref(''), date = ref(''), type = ref('all'), cache = ref([]), before = ref(''), finished = ref(false), loading = ref(false), error = ref(false), limit = ref(20);
let controller = null, generation = 0, timer = null, alive = true;
const types = [
  { value: 'all', label: 'historyFinder.all', icon: Clock },
  { value: 'text', label: 'historyFinder.text', icon: ChatLineSquare },
  { value: 'image', label: 'historyFinder.image', icon: Picture },
  { value: 'video', label: 'historyFinder.video', icon: VideoCamera },
  { value: 'file', label: 'historyFinder.file', icon: Document },
  { value: 'audio', label: 'historyFinder.audio', icon: Microphone },
];
const matches = computed(() => cache.value.filter(message => matchesHistoryMessage(message, { keyword: keyword.value, date: date.value, type: type.value })));
const results = computed(() => matches.value.slice(0, limit.value));
const hasMore = computed(() => matches.value.length > limit.value || !finished.value);
const attachments = message => (message.parts || []).filter(part => ['image', 'video', 'audio', 'file'].includes(part.type) && part.attachment_id);
const senderLabel = type => ({ user: 'chatHistory.user', tool: 'chatHistory.tool', assistant: 'chatHistory.assistant', ai: 'chatHistory.assistant', support_agent: 'support.agent', expert: 'support.expert', system: 'support.system' })[String(type).toLowerCase()] || 'support.unknown';
const mediaKinds = message => [...new Set((message.parts || []).filter(part => ['image', 'video', 'audio', 'file'].includes(part.type)).map(part => part.type))];
function formatTime(value) {
  return formatBeijingTime(value, i18n.locale.replace('_', '-'), { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) || '—';
}
function cancel() { ++generation; controller?.abort(); loading.value = false; }
async function search(reset = true) {
  clearTimeout(timer); cancel();
  if (reset) limit.value = 20;
  const current = generation, request = new AbortController(), scope = props.scopeKey;
  controller = request; loading.value = true; error.value = false;
  const isCurrent = () => alive && current === generation && scope === props.scopeKey && !request.signal.aborted;
  try {
    while (matches.value.length < limit.value && !finished.value) {
      const page = await props.loadPage(before.value, request.signal);
      if (!isCurrent()) return;
      const next = String(page.next_before);
      if (page.has_more && (!/^\d+$/.test(next) || (before.value && BigInt(next) >= BigInt(before.value)))) throw new Error('INVALID_HISTORY_CURSOR');
      const ids = new Set(cache.value.map(message => message.id));
      cache.value = [...cache.value, ...page.items.filter(message => { if (ids.has(message.id)) return false; ids.add(message.id); return true; })];
      before.value = next; finished.value = !page.has_more;
    }
  } catch (failure) { if (isCurrent() && failure.name !== 'AbortError') error.value = true; }
  finally { if (isCurrent()) loading.value = false; }
}
function submitSearch() { search(); }
function showMore() { limit.value += 20; search(false); }
watch([keyword, date, type], (values, previous) => {
  clearTimeout(timer); cancel(); limit.value = 20; error.value = false; loading.value = true;
  timer = setTimeout(() => search(), values[0] !== previous[0] ? 250 : 0);
}, { flush: 'sync' });
watch(() => props.scopeKey, () => {
  clearTimeout(timer); cancel(); cache.value = []; before.value = ''; finished.value = false;
  keyword.value = ''; date.value = ''; type.value = 'all'; search();
}, { flush: 'sync' });
onMounted(() => search());
onBeforeUnmount(() => { alive = false; clearTimeout(timer); cancel(); });
</script>

<style scoped>
.history-finder{height:100%;min-height:0;display:flex;flex-direction:column;gap:16px;text-align:left;color:var(--el-text-color-primary)}
.finder-scope{display:grid;gap:7px}.finder-scope strong{font-size:var(--admin-font-base);overflow-wrap:anywhere}.finder-scope span{font-size:var(--admin-font-xs);color:var(--el-text-color-secondary)}
.finder-search{display:flex;align-items:center;gap:10px;padding:5px 5px 5px 12px;background:var(--el-fill-color-light);border:1px solid var(--el-border-color-lighter);border-radius:8px}
.finder-search>svg{width:18px;height:18px;flex-shrink:0;color:var(--el-text-color-secondary)}.finder-search input{min-width:0;flex:1;border:0;outline:none;background:transparent;font:inherit;font-size:var(--admin-font-base);height:30px;color:inherit}.finder-search:focus-within{border-color:var(--el-color-primary)}
.history-finder button{font:inherit;cursor:pointer}.finder-search button{border:0;border-radius:5px;background:var(--el-color-primary);color:white;font-size:var(--admin-font-xs);padding:8px 12px}
.finder-types{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.finder-types button{display:flex;align-items:center;justify-content:center;gap:7px;min-height:40px;padding:7px;border:1px solid var(--el-border-color-lighter);border-radius:6px;background:var(--el-bg-color);color:var(--el-text-color-regular);font-size:var(--admin-font-base)}.finder-types svg{width:17px;height:17px;flex-shrink:0}.finder-types button[aria-pressed="true"]{color:var(--el-color-primary);background:var(--el-color-primary-light-9);border-color:var(--el-color-primary-light-7)}
.finder-date{display:flex;flex-wrap:wrap;align-items:center;gap:9px;font-size:var(--admin-font-xs)}.finder-date label{display:flex;align-items:center;gap:6px;color:var(--el-text-color-regular)}.finder-date svg{width:15px;height:15px}.finder-date input{flex:1;min-width:125px;border:1px solid var(--el-border-color);border-radius:5px;padding:8px;background:var(--el-bg-color);color:inherit;font:inherit}.finder-date button{border:0;background:transparent;color:var(--el-color-primary);font-size:var(--admin-font-xs);padding:4px}
.finder-results-heading{display:flex;justify-content:space-between;align-items:center;gap:8px;font-size:var(--admin-font-xs);border-top:1px solid var(--el-border-color-lighter);padding-top:16px}.finder-results-heading strong{font-weight:500}.finder-results-heading>span{font-size:var(--admin-font-xs);color:var(--el-text-color-secondary)}
.finder-results{flex:1;min-height:0;overflow:auto;overscroll-behavior:contain;overflow-anchor:none;margin:0 -6px;padding:0 6px}.finder-empty{display:grid;justify-items:center;gap:10px;color:var(--el-text-color-secondary);text-align:center;font-size:var(--admin-font-base);padding:50px 16px}.finder-empty svg{width:30px;height:30px;color:var(--el-border-color-darker)}.finder-empty p{margin:0;line-height:1.8}
.finder-record{border-bottom:1px solid var(--el-border-color-lighter);padding:0 0 12px;margin:0 0 12px}.finder-record-main{display:block;width:100%;border:0;border-radius:6px;padding:10px 8px;text-align:left;background:transparent;color:inherit}.finder-record-main:hover{background:var(--el-fill-color-light)}.finder-record-main:disabled{opacity:.6;cursor:wait}
.finder-record-meta{display:flex;justify-content:space-between;align-items:center;gap:10px;margin-bottom:9px}.finder-record-meta strong{font-size:var(--admin-font-xs);font-weight:500}.finder-record-meta time{font-size:var(--admin-font-xs);color:var(--el-text-color-secondary)}
.finder-record-text{display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;white-space:pre-wrap;overflow-wrap:anywhere;font-size:var(--admin-font-base);line-height:1.8}.finder-record-text mark{color:var(--el-color-primary);background:var(--el-color-primary-light-9);border-radius:2px}
.finder-record-bottom{display:flex;justify-content:space-between;gap:8px;margin-top:10px;font-size:var(--admin-font-xs)}.finder-record-badges{display:flex;flex-wrap:wrap;gap:5px}.finder-record-badges>span{background:var(--el-fill-color);color:var(--el-text-color-secondary);border-radius:4px;padding:2px 6px}.finder-locate{display:flex;align-items:center;gap:3px;color:var(--el-color-primary);margin-left:auto;flex-shrink:0}.finder-locate svg{width:12px;height:12px}
.finder-record-attachments{margin:2px 8px 0;font-size:var(--admin-font-xs);color:var(--el-text-color-regular)}.finder-record-attachments summary{cursor:pointer;color:var(--el-color-primary);padding:4px 0}.finder-more{display:block;width:100%;border:0;background:var(--el-fill-color-light);color:var(--el-color-primary);border-radius:6px;padding:12px;font-size:var(--admin-font-xs)}.finder-more:disabled{cursor:wait;opacity:.6}.finder-end{text-align:center;color:var(--el-text-color-secondary);font-size:var(--admin-font-xs);padding:12px}.finder-error{font-size:var(--admin-font-xs);line-height:1.7;background:var(--el-color-danger-light-9);color:var(--el-color-danger);border-radius:6px;padding:10px 12px;margin-bottom:12px}.finder-error p{margin:0 0 6px}.finder-error button{border:0;background:transparent;color:var(--el-color-primary);padding:0}
.history-finder button:focus-visible,.finder-date input:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
</style>
<style>
.history-search-drawer{max-width:100vw}
.history-search-drawer .el-drawer__header{margin-bottom:0;padding-bottom:18px;border-bottom:1px solid var(--el-border-color-lighter);color:var(--el-text-color-primary)}.history-search-drawer .el-drawer__title{font-size:var(--admin-font-md);font-weight:600}.history-search-drawer .el-drawer__body{min-height:0;padding:20px}
@media(max-width:480px){.history-search-drawer .el-drawer__body{padding:16px}}
</style>
