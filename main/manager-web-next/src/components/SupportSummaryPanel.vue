<template>
  <details class="summary-panel" open>
    <summary>内部交接摘要 <small v-if="selected">· v{{ selected.version }} · {{ selected.origin === 'staff_edit' ? `客服修订 ${selected.edited_by}` : '原文摘录草稿' }}</small></summary>
    <p class="note">仅供客服参考，不发送给用户。原文草稿未作语义归类或技术核验；用户陈述、AI 推测与客服核验分别标注，不取代人工判断。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取摘要…</p>
    <p v-else-if="!revisions.length">暂无交接摘要。<el-button v-if="writable" :loading="saving" @click="prepare">创建原文草稿</el-button></p>
    <template v-if="selected">
      <div class="summary-toolbar">
        <el-select v-model="selectedVersion" aria-label="摘要版本" :disabled="saving || editing" @change="selectVersion"><el-option v-for="revision in revisions" :key="revision.version" :value="revision.version" :label="`v${revision.version} · ${revision.origin === 'staff_edit' ? '客服修订' : '原文摘录草稿'}`" /></el-select>
        <el-button v-if="more" :disabled="loading || editing" @click="loadMore">更早版本</el-button>
        <el-button v-if="writable && selected === revisions[0] && !editing" @click="beginEdit">编辑补充</el-button>
        <template v-if="editing"><el-button :loading="saving" @click="save">保存为新版本</el-button><el-button :disabled="saving" @click="cancelEdit">取消编辑</el-button></template>
      </div>
      <small>源消息截至 seq {{ selected.source_through_seq }} · {{ selected.created_at }} <span v-if="selected.source_truncated">· 草稿有截断，请核对原记录</span></small>
      <section v-for="field in fields" :key="field.key" class="summary-field">
        <strong>{{ field.label }}</strong>
        <div v-for="(item, index) in displayedFields[field.key]" :key="index" class="summary-item">
          <small>{{ labels[item.attribution] || item.attribution }}</small>
          <template v-if="editing">
            <el-input :disabled="saving" v-model="item.text" type="textarea" :rows="2" maxlength="2000" :aria-label="`${field.label}内容`" />
            <el-select :disabled="saving" v-model="item.attribution" :aria-label="`${field.label}来源类型`"><el-option v-for="kind in kinds(item, field.key)" :key="kind" :value="kind" :label="labels[kind]" /></el-select>
            <el-input v-if="item.attribution === 'verified_fact'" v-model="item.verification_note" maxlength="1000" placeholder="说明如何由原证据核验（记录本次客服身份）" aria-label="事实核验依据" />
            <el-button :disabled="saving" @click="removeItem(field.key, index)">移除此项</el-button>
          </template>
          <p v-else>{{ item.text }}</p>
          <p v-if="item.verification_note && !editing" class="note">客服核验说明：{{ item.verification_note }}</p>
          <div v-for="ref in item.evidence" :key="`${ref.message_id}:${ref.attachment_id || ref.quote}`" class="evidence">
            <el-button link @click="$emit('source', ref)">查看原消息 · seq {{ ref.seq }}<span v-if="ref.attachment_id"> · 附件 {{ ref.attachment_id }}</span></el-button>
            <blockquote v-if="ref.quote">{{ ref.quote }}</blockquote>
          </div>
        </div>
        <div v-if="editing" class="summary-toolbar">
          <el-select :disabled="saving" v-model="sourceChoices[field.key]" :aria-label="`${field.label}引用原消息`" placeholder="选择已有原文或附件"><el-option v-for="source in sources" :key="source.key" :value="source.key" :label="source.label" /></el-select>
          <el-button :disabled="saving || !sourceChoices[field.key] || displayedFields[field.key].length >= 20" @click="addEvidence(field.key)">引用补充</el-button>
          <el-button :disabled="saving || displayedFields[field.key].length >= 20" @click="addUnknown(field.key)">添加待确认项</el-button>
        </div>
      </section>
    </template>
  </details>
</template>
<script>
import supportApi from '../apis/module/support';
const labels = { unknown: '待确认', user_statement: '用户陈述（未独立核验）', staff_statement: '客服原文（未独立核验）', staff_hypothesis: '客服推测', ai_inference: 'AI 推测（不可作为事实）', evidence_reference: '原始证据引用（未核验内容）', verified_fact: '客服核验事实（见核验说明）' };
const fields = [ ['user_goal','用户诉求（草稿为用户原文，待归类）'], ['project/equipment','项目 / 设备'], ['symptoms','症状'], ['evidence_attachments','证据附件'], ['verified_facts','已核验事实'], ['hypotheses','推测'], ['attempted_steps','已尝试步骤'], ['missing_information','缺失信息'], ['safety_flags','安全风险'] ].map(([key,label])=>({key,label}));
export default {
  name: 'SupportSummaryPanel',
  props: { conversationId: { type: String, required: true }, messages: { type: Array, default: () => [] }, writable: Boolean },
  emits: ['source'],
  data: () => ({ labels, fields, revisions: [], selectedVersion: null, edited: null, sourceChoices: {}, loading: false, saving: false, error: '', more: false, controller: null, epoch: 0 }),
  computed: {
    selected() { return this.revisions.find(row => String(row.version) === String(this.selectedVersion)); },
    editing() { return !!this.edited; },
    displayedFields() { return this.edited || this.selected?.fields || {}; },
    sources() {
      return this.messages.flatMap(message => message.parts.map((part,index) => {
        if(part.type !== 'text' && !part.attachment_id) return null;
        const quote = part.type === 'text' ? part.text?.slice(0,500) : null;
        if(!quote && !part.attachment_id) return null;
        return { key: `${message.id}:${index}`, sender: message.sender_type, label: `seq ${message.seq} · ${message.sender_type} · ${quote?.slice(0,50) || `附件 ${part.attachment_id}`}`, evidence: { message_id: message.id, seq: message.seq, attachment_id: part.attachment_id || null, quote } };
      }).filter(Boolean));
    },
  },
  watch: { conversationId() { this.reset(); }, writable() { if(!this.writable) this.reset(); } },
  mounted() { this.reset(); },
  beforeUnmount() { ++this.epoch; this.controller?.abort(); },
  methods: {
    reset() { ++this.epoch; this.controller?.abort(); this.controller = new AbortController(); this.revisions = []; this.selectedVersion = null; this.edited = null; this.sourceChoices = {}; this.error = ''; this.more = false; this.loading = false; this.saving = false; this.loadMore(); },
    async loadMore() {
      if(this.loading) return; this.loading = true; const epoch = this.epoch;
      try { const rows = await supportApi.summaries(this.conversationId,this.revisions.at(-1)?.version || '0',this.controller.signal); if(epoch !== this.epoch) return; this.revisions.push(...rows); this.more = rows.length === 20; if(this.selectedVersion === null) this.selectedVersion = rows[0]?.version ?? null; }
      catch(error) { if(epoch === this.epoch && error.name !== 'AbortError') this.error = error.message; }
      finally { if(epoch === this.epoch) this.loading = false; }
    },
    selectVersion() { this.edited = null; this.error = ''; },
    beginEdit() { if(this.writable && this.selected === this.revisions[0]) this.edited = JSON.parse(JSON.stringify(this.selected.fields)); },
    cancelEdit() { this.edited = null; this.error = ''; },
    kinds(item, field) { if(item.attribution === 'unknown') return ['unknown']; if(item.attribution === 'ai_inference') return ['ai_inference']; return field === 'verified_facts' ? ['verified_fact'] : ['user_statement','staff_statement','staff_hypothesis','evidence_reference']; },
    removeItem(field,index) { if(this.saving) return; this.edited[field].splice(index,1); if(!this.edited[field].length) this.addUnknown(field); },
    addUnknown(field) { if(this.saving) return; this.edited[field].push({ text: '待确认', attribution: 'unknown', evidence: [], verification_note: null }); },
    addEvidence(field) {
      if(this.saving) return;
      const source = this.sources.find(row => row.key === this.sourceChoices[field]); if(!source) return;
      const ai = ['assistant','ai'].includes(source.sender);
      if(ai && field !== 'hypotheses') { this.error = 'AI 原文只能归入推测，不能作为已核验事实。'; return; }
      const attribution = ai ? 'ai_inference' : field === 'verified_facts' ? 'verified_fact' : source.evidence.attachment_id ? 'evidence_reference' : source.sender === 'user' ? 'user_statement' : 'staff_statement';
      this.edited[field].push({ text: source.evidence.quote || '原始附件（尚未核验内容）', attribution, evidence: [JSON.parse(JSON.stringify(source.evidence))], verification_note: null });
    },
    async prepare() {
      if(!this.writable || this.saving) return; this.saving = true; this.error = ''; const epoch = this.epoch;
      try { const revision = await supportApi.prepareSummary(this.conversationId,this.controller.signal); if(epoch !== this.epoch) return; this.revisions = [revision]; this.selectedVersion = revision.version; }
      catch(error) { if(epoch === this.epoch && error.name !== 'AbortError') this.error = error.message; }
      finally { if(epoch === this.epoch) this.saving = false; }
    },
    async save() {
      if(!this.writable || !this.edited || this.saving) return; this.saving = true; this.error = ''; const epoch = this.epoch;
      try { const revision = await supportApi.editSummary(this.conversationId,{ base_version: this.selected.version, fields: this.edited },this.controller.signal); if(epoch !== this.epoch) return; this.revisions.unshift(revision); this.selectedVersion = revision.version; this.edited = null; }
      catch(error) { if(epoch === this.epoch && error.name !== 'AbortError') this.error = error.code === 'SUMMARY_VERSION_CONFLICT' ? '摘要已有新版本，本地编辑已保留；请复制补充内容后重新选择会话读取最新版本。' : error.message; }
      finally { if(epoch === this.epoch) this.saving = false; }
    },
  },
};
</script>
<style scoped>
.summary-panel { margin: 14px 0; padding: 12px; border: 1px solid #e6ebf3; border-radius: 8px; }
summary { cursor: pointer; font-weight: 600; } .note,small { color: #7b8ca5; font-size: 12px; }
.summary-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 8px 0; } .el-select { min-width: 180px; max-width: 100%; }
.summary-field { margin: 12px 0; border-top: 1px solid #edf1f5; padding-top: 8px; } .summary-item { padding: 8px 0; }
p,blockquote { white-space: pre-wrap; overflow-wrap: anywhere; } blockquote { margin: 4px 0; padding-left: 10px; border-left: 2px solid #dce3ec; color: #7b8ca5; font-size: 12px; }
</style>
