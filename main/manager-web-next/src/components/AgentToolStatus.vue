<template>
  <section v-if="conversationId && !unavailable" class="agent-tool-status" aria-live="polite">
    <details>
      <summary>{{ $t('toolStatus.title') }} <span v-if="displayItems.length">({{ displayItems.length }})</span></summary>
      <p v-if="error" role="alert">{{ $t('toolStatus.error') }} <button type="button" @click="refresh">{{ $t('toolStatus.retry') }}</button></p>
      <p v-else-if="loading && !items.length">{{ $t('toolStatus.loading') }}</p>
      <p v-else-if="!items.length">{{ $t('toolStatus.empty') }}</p>
      <ol v-if="items.length">
        <li v-for="item in displayItems" :key="item.call_id">
          <div><strong>{{ item.name }}</strong> <span class="status">{{ statusLabel(item.status) }}</span></div>
          <small>{{ item.created_at }} · {{ $t('toolStatus.attempt') }} {{ item.attempt }} · {{ $t('toolStatus.round') }} {{ item.round }}</small>
          <small v-if="item.error_code">{{ $t('toolStatus.errorCode') }}: {{ item.error_code }}</small>
        </li>
      </ol>
      <button v-if="hasMore" type="button" :disabled="loading" @click="loadMore">{{ $t('toolStatus.more') }}</button>
    </details>
  </section>
</template>
<script>
import api from '../apis/module/toolStatus';
const validSequence = value => (typeof value === 'string' && /^[0-9]+$/.test(value)) || (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0);
export default {
  name: 'AgentToolStatus',
  props: { conversationId: { type: String, default: '' }, identity: { type: String, default: '' } },
  data: () => ({ items: [], cursor: '0', hasMore: false, unavailable: false, error: false, loading: false, epoch: 0, controller: null, timer: null }),
  computed: {
    displayItems() {
      const calls = new Map();
      for (const item of this.items) if (!calls.has(item.call_id)) calls.set(item.call_id, item);
      return [...calls.values()];
    },
  },
  watch: { conversationId: 'reset', identity: 'reset' },
  mounted() { this.reset(); },
  beforeUnmount() { this.epoch++; this.controller?.abort(); clearInterval(this.timer); },
  methods: {
    statusLabel(value) { return ['running','succeeded','failed','discarded'].includes(value) ? this.$t(`toolStatus.${value}`) : value; },
    reset() {
      this.epoch++; this.controller?.abort(); clearInterval(this.timer);
      this.items = []; this.cursor = '0'; this.hasMore = false; this.unavailable = false; this.error = false; this.loading = false;
      if (this.conversationId && this.identity) { this.refresh(); this.timer = setInterval(() => this.refresh(), 5000); }
    },
    async refresh() { await this.read(false); },
    async loadMore() { if (this.hasMore) await this.read(true); },
    async read(older) {
      if (this.loading || !this.conversationId || !this.identity || this.unavailable) return;
      const epoch = this.epoch, controller = new AbortController(), cursor = older ? this.cursor : '0';
      this.controller = controller; this.loading = true;
      try {
        const page = await api.list(this.conversationId, cursor, controller.signal);
        if (epoch !== this.epoch || controller.signal.aborted) return;
        if (!page || !Array.isArray(page.items) || !validSequence(page.next_cursor) || typeof page.has_more !== 'boolean' || page.items.some(item => !item.call_id || !validSequence(item.sequence))) throw new Error('INVALID_TOOL_PAGE');
        const existing = new Set(this.items.map(item => String(item.sequence)));
        const disjointHead = !older && this.items.length > 0 && page.items.length > 0 && !page.items.some(item => existing.has(String(item.sequence)));
        const merged = new Map(this.items.map(item => [String(item.sequence), item]));
        for (const item of page.items) merged.set(String(item.sequence), item);
        this.items = [...merged.values()].sort((a,b) => BigInt(String(a.sequence)) > BigInt(String(b.sequence)) ? -1 : BigInt(String(a.sequence)) < BigInt(String(b.sequence)) ? 1 : 0);
        if (older || this.cursor === '0' || (disjointHead && page.has_more)) { this.cursor = String(page.next_cursor); this.hasMore = page.has_more; }
        this.error = false;
      } catch (error) {
        if (epoch !== this.epoch || controller.signal.aborted) return;
        if (error.status === 404) { this.unavailable = true; this.items = []; clearInterval(this.timer); }
        else this.error = true;
      } finally { if (epoch === this.epoch) { this.loading = false; this.controller = null; } }
    },
  },
};
</script>
<style scoped>
.agent-tool-status { border: 1px solid #dce8f6; border-radius: 8px; background: #f8fbff; padding: 8px 12px; color: #274563; font-size: 13px; }
summary { cursor: pointer; font-weight: 600; }
ol { list-style: none; padding: 0; margin: 8px 0; max-height: 180px; overflow-y: auto; }
li { padding: 6px 0; border-top: 1px solid #e4edf7; overflow-wrap: anywhere; }
small { display: block; color: #64788e; margin-top: 3px; }
.status { color: #2866a3; margin-left: 5px; }
button { background: none; border: 0; color: #2866a3; cursor: pointer; padding: 2px; }
p { margin: 8px 0; }
</style>
