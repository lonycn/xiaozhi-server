<template>
  <details class="summary-panel" open>
    <summary>内部交接摘要 <small v-if="selected">· v{{ selected.version }} · {{ originLabel(selected) }}</small></summary>
    <p class="note">仅供客服参考，不发送给用户。原文草稿未作语义归类或技术核验；用户陈述、AI 推测与客服核验分别标注，不取代人工判断。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取摘要…</p>
    <p v-else-if="!revisions.length">暂无交接摘要。<el-button v-if="writable" :loading="saving" @click="prepare">创建原文草稿</el-button></p>
    <template v-if="selected">
      <div class="summary-toolbar">
        <el-select v-model="selectedVersion" aria-label="摘要版本" :disabled="saving || editing" @change="selectVersion"><el-option v-for="revision in revisions" :key="revision.version" :value="revision.version" :label="`v${revision.version} · ${originLabel(revision)}`" /></el-select>
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
    <section v-if="writable" class="assistance">
      <strong>AI 辅助整理（仅内部）</strong>
      <p class="note">由客服主动发起；模型内容和建议是推测，请核对原始证据。加入回复草稿后仍需人工编辑并发送。</p>
      <p v-if="assistanceAvailable === false">AI 辅助整理暂不可用。</p>
      <template v-else-if="assistanceAvailable">
        <el-button :disabled="!selected || selected !== revisions[0] || editing || assistanceBusy || ['queued','running'].includes(job?.status)" @click="createAssistance">AI辅助整理</el-button>
        <p v-if="job" role="status">{{ jobStatus(job) }}<span v-if="job.error_code"> · {{ jobError(job.error_code) }}</span></p>
        <el-button v-if="job?.status === 'failed'" :disabled="assistanceBusy || !!assistanceError" @click="retryAssistance">重试整理</el-button>
        <el-button v-if="job && assistanceError" :disabled="assistanceBusy" @click="pollJob">重新查询状态</el-button>
        <p v-if="assistanceError" role="alert">{{ assistanceError }}</p>
        <div v-if="job?.status === 'completed'" class="suggestions">
          <div v-for="(suggestion,index) in job.suggestions || []" :key="index" class="summary-item">
            <small>AI 推测（仅内部，需核验）</small><p>{{ suggestion.text }}</p>
            <div v-for="ref in suggestion.evidence || []" :key="`${ref.message_id}:${ref.attachment_id || ref.quote}`" class="evidence">
              <el-button link @click="$emit('source', ref)">查看原消息 · seq {{ ref.seq }}<span v-if="ref.attachment_id"> · 附件 {{ ref.attachment_id }}</span></el-button>
              <blockquote v-if="ref.quote">{{ ref.quote }}</blockquote>
            </div>
            <el-button @click="$emit('suggestion', { conversation_id: conversationId, text: suggestion.text })">加入回复草稿</el-button>
          </div>
        </div>
      </template>
      <p v-else>{{ assistanceError || '正在检查 AI 辅助功能…' }} <el-button v-if="assistanceError" @click="loadAssistance">重新检查</el-button></p>
    </section>
  </details>
</template>
<script>
import supportApi from '../apis/module/support';
const labels = { unknown: '待确认', user_statement: '用户陈述（未独立核验）', staff_statement: '客服原文（未独立核验）', staff_hypothesis: '客服推测', ai_inference: 'AI 推测（不可作为事实）', evidence_reference: '原始证据引用（未核验内容）', verified_fact: '客服核验事实（见核验说明）' };
const fields = [ ['user_goal','用户诉求（草稿为用户原文，待归类）'], ['project/equipment','项目 / 设备'], ['symptoms','症状'], ['evidence_attachments','证据附件'], ['verified_facts','已核验事实'], ['hypotheses','推测'], ['attempted_steps','已尝试步骤'], ['missing_information','缺失信息'], ['safety_flags','安全风险'] ].map(([key,label])=>({key,label}));
function requestId() { const bytes = new Uint8Array(16); crypto.getRandomValues(bytes); bytes[6] = (bytes[6] & 15) | 64; bytes[8] = (bytes[8] & 63) | 128; const hex = Array.from(bytes, value => value.toString(16).padStart(2,'0')).join(''); return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`; }
function newerMessage(messages, through) { if(!/^\d+$/.test(String(through ?? ''))) return false; return messages.some(message => /^\d+$/.test(String(message.seq ?? '')) && BigInt(message.seq) > BigInt(through)); }
export default {
  name: 'SupportSummaryPanel',
  props: { conversationId: { type: String, required: true }, messages: { type: Array, default: () => [] }, writable: Boolean },
  emits: ['source', 'suggestion'],
  data: () => ({ labels, fields, revisions: [], revisionGeneration: 0, selectedVersion: null, edited: null, sourceChoices: {}, loading: false, saving: false, error: '', more: false, controller: null, epoch: 0, assistanceController: null, assistanceEpoch: 0, assistanceAvailable: null, assistanceBusy: false, assistanceError: '', job: null, pollTimer: null, pendingCreates: {} }),
  computed: {
    selected() { return this.revisions.find(row => String(row.version) === String(this.selectedVersion)); },
    editing() { return !!this.edited; },
    messageScope() { return this.messages.map(message => `${message.id}:${message.seq}`).join('|'); },
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
  watch: { conversationId() { this.reset(); }, writable() { this.reset(); }, messageScope() { this.invalidateAssistance(); this.assistanceAvailable = null; if(this.writable) this.loadAssistance(); } },
  mounted() { this.reset(); },
  beforeUnmount() { ++this.epoch; ++this.assistanceEpoch; clearTimeout(this.pollTimer); this.controller?.abort(); this.assistanceController?.abort(); },
  methods: {
    reset() { ++this.epoch; ++this.revisionGeneration; this.controller?.abort(); this.controller = new AbortController(); this.invalidateAssistance(); this.revisions = []; this.selectedVersion = null; this.edited = null; this.sourceChoices = {}; this.error = ''; this.more = false; this.loading = false; this.saving = false; this.assistanceAvailable = null; this.assistanceError = ''; this.loadMore(); if(this.writable) this.loadAssistance(); },
    invalidateAssistance() { ++this.assistanceEpoch; clearTimeout(this.pollTimer); this.assistanceController?.abort(); this.assistanceController = new AbortController(); this.job = null; this.assistanceBusy = false; this.assistanceError = ''; },
    refreshAssistance() { this.invalidateAssistance(); this.assistanceAvailable = null; if(this.writable) this.loadAssistance(); },
    originLabel(revision) { return revision.origin === 'staff_edit' ? `客服修订 ${revision.edited_by || ''}` : revision.origin === 'model_assisted' ? '模型辅助整理（需核验）' : '原文摘录草稿'; },
    jobStatus(job) { if(job.status === 'stale') return job.result_version != null ? '来源已变化，历史整理已保留，请重新发起' : '源摘要已变化，本次结果未写入，请重新发起'; return ({ queued:'排队中', running:'正在整理', completed:'整理完成', failed:'整理失败' })[job.status] || '状态未知'; },
    jobError(code) { return ({ INVALID_MODEL_OUTPUT:'模型结果未通过证据核验，请检查来源后重试', PROVIDER_TIMEOUT:'模型响应超时，请稍后重试', PROVIDER_AUTH:'模型服务认证失败，请联系管理员检查配置', PROVIDER_RATE_LIMIT:'模型服务繁忙，请稍后重试', PROVIDER_UNAVAILABLE:'模型服务暂不可用，请稍后重试', PROVIDER_ERROR:'模型服务处理失败，请稍后重试', PROVIDER_OUTPUT_LIMIT:'模型输出超过限制，请检查来源后重试', WORKER_ERROR:'整理任务处理失败，请稍后重试', RETRY_EXHAUSTED:'重试次数已用完，请重新发起整理', SOURCE_CHANGED:'来源已变化，请重新发起整理' })[code] || '整理任务未完成，请检查后重试'; },
    assistanceFailure(error) { if(error.status === 404) { this.assistanceAvailable = false; this.job = null; clearTimeout(this.pollTimer); } else this.assistanceError = error.message || '暂时无法获取整理状态，请重试。'; },
    async loadAssistance() {
      const epoch = this.assistanceEpoch; this.assistanceError = '';
      try { const result = await supportApi.summaryAssistance(this.conversationId, this.assistanceController.signal); if(epoch !== this.assistanceEpoch) return; this.assistanceAvailable = result.enabled === true; if(this.assistanceAvailable && result.latest_job) await this.acceptJob(result.latest_job, epoch); }
      catch(error) { if(epoch === this.assistanceEpoch && error.name !== 'AbortError') this.assistanceFailure(error); }
    },
    async acceptJob(job, epoch) {
      if(epoch !== this.assistanceEpoch) return; this.job = job; this.assistanceError = ''; clearTimeout(this.pollTimer);
      if(['queued','running'].includes(job.status)) this.pollTimer = setTimeout(() => this.pollJob(), 2000);
      if(job.status === 'completed') {
        const generation = this.revisionGeneration;
        const rows = await supportApi.summaries(this.conversationId, '0', this.assistanceController.signal);
        if(epoch !== this.assistanceEpoch) return;
        const current = generation === this.revisionGeneration ? rows : this.revisions;
        if((job.result_version != null && String(current[0]?.version) !== String(job.result_version)) || newerMessage(this.messages, job.source_through_seq)) this.job = { ...job, status: 'stale', suggestions: [] };
        if(generation !== this.revisionGeneration) return;
        const editingRevision = this.editing ? this.selected : null;
        ++this.revisionGeneration; this.revisions = editingRevision && !rows.some(row => String(row.version) === String(editingRevision.version)) ? [...rows, editingRevision] : rows; this.more = rows.length === 20;
        if(!this.editing) this.selectedVersion = rows[0]?.version ?? null;
      }
    },
    async createAssistance() {
      if(!this.writable || this.assistanceAvailable !== true || !this.selected || this.selected !== this.revisions[0] || this.editing || this.assistanceBusy || ['queued','running'].includes(this.job?.status)) return;
      const epoch = this.assistanceEpoch, key = `${this.conversationId}:${this.selected.version}`;
      const payload = this.pendingCreates[key] || (this.pendingCreates[key] = { client_request_id: requestId(), base_version: this.selected.version });
      this.assistanceBusy = true; this.assistanceError = ''; this.job = null;
      try { const job = await supportApi.createSummaryAssistance(this.conversationId, payload, this.assistanceController.signal); if(epoch !== this.assistanceEpoch) return; delete this.pendingCreates[key]; await this.acceptJob(job, epoch); }
      catch(error) { if(epoch === this.assistanceEpoch && error.name !== 'AbortError') { if(error.status && error.status < 500 && error.status !== 408 && error.status !== 429) delete this.pendingCreates[key]; this.assistanceFailure(error); } }
      finally { if(epoch === this.assistanceEpoch) this.assistanceBusy = false; }
    },
    async pollJob() {
      if(!this.writable || !this.job || this.assistanceBusy) return; const epoch = this.assistanceEpoch, id = this.job.id; this.assistanceBusy = true; clearTimeout(this.pollTimer);
      try { const job = await supportApi.summaryAssistanceJob(this.conversationId, id, this.assistanceController.signal); if(epoch === this.assistanceEpoch) await this.acceptJob(job, epoch); }
      catch(error) { if(epoch === this.assistanceEpoch && error.name !== 'AbortError') this.assistanceFailure(error); }
      finally { if(epoch === this.assistanceEpoch) this.assistanceBusy = false; }
    },
    async retryAssistance() {
      if(!this.writable || this.job?.status !== 'failed' || this.assistanceBusy || this.assistanceError) return; const epoch = this.assistanceEpoch; this.assistanceBusy = true; this.assistanceError = '';
      try { const job = await supportApi.retrySummaryAssistance(this.conversationId, this.job.id, this.assistanceController.signal); if(epoch === this.assistanceEpoch) await this.acceptJob(job, epoch); }
      catch(error) { if(epoch === this.assistanceEpoch && error.name !== 'AbortError') this.assistanceFailure(error); }
      finally { if(epoch === this.assistanceEpoch) this.assistanceBusy = false; }
    },
    async loadMore() {
      if(this.loading) return; this.loading = true; const epoch = this.epoch, generation = this.revisionGeneration;
      try { const before = this.revisions.at(-1)?.version || '0'; const rows = await supportApi.summaries(this.conversationId,before,this.controller.signal); if(epoch !== this.epoch || generation !== this.revisionGeneration) return; this.revisions.push(...rows.filter(row => !this.revisions.some(existing => String(existing.version) === String(row.version)))); this.more = rows.length === 20; if(this.selectedVersion === null) this.selectedVersion = rows[0]?.version ?? null; }
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
      try { const revision = await supportApi.prepareSummary(this.conversationId,this.controller.signal); if(epoch !== this.epoch) return; ++this.revisionGeneration; this.revisions = [revision]; this.selectedVersion = revision.version; this.refreshAssistance(); }
      catch(error) { if(epoch === this.epoch && error.name !== 'AbortError') this.error = error.message; }
      finally { if(epoch === this.epoch) this.saving = false; }
    },
    async save() {
      if(!this.writable || !this.edited || this.saving) return; this.saving = true; this.error = ''; const epoch = this.epoch;
      try { const revision = await supportApi.editSummary(this.conversationId,{ base_version: this.selected.version, fields: this.edited },this.controller.signal); if(epoch !== this.epoch) return; ++this.revisionGeneration; this.revisions.unshift(revision); this.selectedVersion = revision.version; this.edited = null; this.refreshAssistance(); }
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
