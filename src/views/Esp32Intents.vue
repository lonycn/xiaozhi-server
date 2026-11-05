<template>
  <section class="admin-page intent-page" v-loading="busy">
    <header class="operation-bar">
      <div>
        <h2 class="page-title">{{ t('esp32Intent.title') }}</h2>
        <p class="intro">{{ t('esp32Intent.intro') }}</p>
      </div>
      <div class="agent-controls">
        <el-select :model-value="agentId" :loading="agentsLoading" :disabled="busy" filterable
          :placeholder="t('esp32Intent.selectAgent')" :aria-label="t('esp32Intent.selectAgent')" @change="selectAgent">
          <el-option v-for="agent in agentOptions" :key="agent.id" :label="agent.agentName" :value="agent.id" />
        </el-select>
        <el-button v-if="agentId" @click="router.push({ path: '/role-config', query: { agentId } })">
          {{ t('roleConfig.title') }}
        </el-button>
      </div>
    </header>

    <el-alert v-if="agentsError" :title="agentsError" type="error" :closable="false" show-icon />
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-empty v-if="!agentId && !agentsLoading && !agentsError"
      :description="t(agents.length ? 'esp32Intent.missingAgent' : 'esp32Intent.noAgents')" />
    <el-button v-if="error && !view" class="retry" @click="reload">{{ t('esp32Intent.reload') }}</el-button>
    <template v-if="view">
      <el-card shadow="never">
        <div class="section-header">
          <div>
            <h3>{{ t('esp32Intent.rules') }}</h3>
            <p class="intro">{{ t(view.publishedVersion > 0 ? 'esp32Intent.version' : 'esp32Intent.noPublished', { version: view.publishedVersion }) }}</p>
          </div>
          <el-tag v-if="dirty || unpublished" type="warning">{{ t(dirty ? 'esp32Intent.unsaved' : 'esp32Intent.unpublished') }}</el-tag>
        </div>
        <div class="rule-tools">
          <el-radio-group v-model="scope" :disabled="busy" :aria-label="t('esp32Intent.ruleScope')">
            <el-radio-button value="draft">{{ t('esp32Intent.draftScope') }}</el-radio-button>
            <el-radio-button value="published">{{ t('esp32Intent.publishedScope') }}</el-radio-button>
          </el-radio-group>
          <el-input v-model="search" clearable :placeholder="t('esp32Intent.search')"
            :aria-label="t('esp32Intent.search')" />
          <el-button v-if="scope === 'draft'" type="primary" plain :disabled="policy.rules.length >= 50 || busy"
            @click="editRule()">{{ t('esp32Intent.add') }}</el-button>
        </div>

        <p class="intro scope-hint">{{ t('esp32Intent.playbackHint') }}</p>
        <div v-for="rule in matchingBuiltIns" :key="rule.id" class="rule-row">
          <div class="rule-body">
            <strong>{{ rule.name }}</strong>
            <div class="phrase-list">
              <el-tag v-for="phrase in rule.phrases" :key="phrase" size="small" effect="plain" type="info">{{ phrase }}</el-tag>
            </div>
          </div>
          <el-tag type="info">{{ t('esp32Intent.builtIn') }}</el-tag>
        </div>
        <div v-for="{ rule, index } in matchingRules" :key="rule.id" class="rule-row">
          <div class="rule-body">
            <strong>{{ rule.name }}</strong>
            <div class="phrase-list">
              <el-tag v-for="phrase in rule.phrases" :key="phrase" size="small" effect="plain">{{ phrase }}</el-tag>
            </div>
            <p v-if="rule.action === 'reply'">{{ rule.reply }}</p>
          </div>
          <div class="row-actions">
            <el-tag :type="rule.enabled ? 'success' : 'info'">
              {{ t(`esp32Intent.${rule.action}`) }} · {{ t(rule.enabled ? 'esp32Intent.on' : 'esp32Intent.off') }}
            </el-tag>
            <el-button v-if="scope === 'draft'" link type="primary" :disabled="busy" @click="editRule(index)">{{ t('esp32Intent.edit') }}</el-button>
          </div>
        </div>
        <p v-if="search.trim() && !matchingBuiltIns.length && !matchingRules.length" class="intro">
          {{ t('esp32Intent.noResults') }}
        </p>
        <p v-else-if="!search.trim() && !displayPolicy.rules.length" class="intro">{{ t('esp32Intent.noRules') }}</p>
        <div v-if="scope === 'draft'" class="timezone">
          <el-form label-position="top" @submit.prevent>
            <el-form-item :label="t('esp32Intent.timezone')">
              <el-input v-model.trim="policy.timezone" :disabled="busy" maxlength="64" placeholder="Asia/Shanghai" />
            </el-form-item>
          </el-form>
        </div>
        <div v-else class="intro timezone-summary">{{ t('esp32Intent.timezone') }} · {{ displayPolicy.timezone }}</div>
        <div v-if="scope === 'draft'" class="actions">
          <el-button :disabled="!dirty || busy" @click="mutate('draft', 'PUT', { revision, policy })">
            {{ t('esp32Intent.save') }}
          </el-button>
          <el-button type="primary" :disabled="dirty || busy || !unpublished" @click="confirmMutation('publish')">
            {{ t('esp32Intent.publish') }}
          </el-button>
          <el-button :disabled="dirty || busy || Number(view.publishedVersion) === 0"
            @click="confirmMutation('rollback')">
            {{ t('esp32Intent.rollback') }}
          </el-button>
          <el-button v-if="error" link @click="reload">{{ t('esp32Intent.reload') }}</el-button>
        </div>
        <p v-if="scope === 'draft'" class="intro">{{ t('esp32Intent.publishHint') }}</p>
      </el-card>

      <el-card shadow="never">
        <div class="section-header">
          <h3>{{ t('esp32Intent.preview') }}</h3>
          <el-tag effect="plain">{{ t(scope === 'draft' ? 'esp32Intent.draftScope' : 'esp32Intent.publishedScope') }}</el-tag>
        </div>
        <el-form label-position="top" @submit.prevent="preview">
          <el-form-item :label="t('esp32Intent.utterance')">
            <el-input v-model="utterance" maxlength="200" :disabled="busy" :placeholder="t('esp32Intent.previewPlaceholder')" />
          </el-form-item>
          <el-button native-type="submit" :disabled="busy || !utterance.trim()">{{ t('esp32Intent.run') }}</el-button>
        </el-form>
        <div v-if="result" class="preview-result" role="status">
          <template v-if="result.matched">
            <strong>{{ result.rule.name }}</strong>
            <span v-if="result.rule.name !== t(`esp32Intent.${result.rule.action}`)">
              · {{ t(`esp32Intent.${result.rule.action}`) }}
            </span>
            <p v-if="result.rule.reply">{{ result.rule.reply }}</p>
          </template>
          <template v-else>{{ t('esp32Intent.unmatched') }}</template>
        </div>
      </el-card>
    </template>

    <el-drawer v-model="editing" :title="t('esp32Intent.edit')" size="min(440px, 100vw)" destroy-on-close>
      <el-form v-if="editor" label-position="top" @submit.prevent="applyEdit">
        <el-form-item :label="t('esp32Intent.name')">
          <el-input v-model.trim="editor.name" maxlength="48" />
        </el-form-item>
        <el-form-item :label="t('esp32Intent.action')">
          <el-select v-model="editor.action">
            <el-option v-for="action in actions" :key="action" :label="t(`esp32Intent.${action}`)" :value="action" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('esp32Intent.phrases')">
          <el-input v-model="editor.phraseText" type="textarea" :rows="5" maxlength="1040" />
        </el-form-item>
        <el-form-item v-if="editor.action === 'reply'" :label="t('esp32Intent.reply')">
          <el-input v-model="editor.reply" type="textarea" :rows="4" maxlength="400" />
        </el-form-item>
        <el-form-item :label="t('esp32Intent.on')">
          <el-switch v-model="editor.enabled" />
        </el-form-item>
        <el-alert v-if="editorError" :title="editorError" type="error" :closable="false" />
        <div class="actions">
          <el-button type="primary" native-type="submit">{{ t('esp32Intent.apply') }}</el-button>
          <el-button v-if="editingIndex >= 0" type="danger" plain @click="removeRule">
            {{ t('esp32Intent.remove') }}
          </el-button>
        </div>
      </el-form>
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authenticatedFetch } from '@/apis/httpRequest'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const agentId = computed(() => String(route.query.agentId || ''))
const agents = ref([])
const agentsLoading = ref(true)
const agentsError = ref('')
const agentOptions = computed(() => agentId.value && !agents.value.some(agent => agent.id === agentId.value)
  ? [...agents.value, { id: agentId.value, agentName: agentId.value }] : agents.value)
const actions = ['exit', 'stop', 'reply', 'time']
const view = ref(null)
const policy = ref(null)
const baseline = ref('')
const busy = ref(false)
const error = ref('')
const editing = ref(false)
const editingIndex = ref(-1)
const editor = ref(null)
const editorError = ref('')
const utterance = ref('')
const result = ref(null)
const scope = ref('draft')
const search = ref('')
const revision = computed(() => view.value?.revision || 0)
const dirty = computed(() => !!policy.value && JSON.stringify(policy.value) !== baseline.value)
const unpublished = computed(() => !!view.value && baseline.value !== JSON.stringify(view.value.published))
const displayPolicy = computed(() => scope.value === 'draft' ? policy.value : view.value?.published)
const query = computed(() => search.value.trim().toLowerCase())
const matchesSearch = rule => !query.value || [rule.name, ...rule.phrases].some(value => value.toLowerCase().includes(query.value))
const matchingBuiltIns = computed(() => (view.value?.builtIns || []).filter(matchesSearch))
const matchingRules = computed(() => (displayPolicy.value?.rules || [])
  .map((rule, index) => ({ rule, index })).filter(({ rule }) => matchesSearch(rule)))
let loadGeneration = 0

async function request(path, method = 'GET', data) {
  const base = import.meta.env.VITE_API_BASE_URL || '/xiaozhi'
  const response = await authenticatedFetch(`${base}${path}`, {
    method, headers: { 'Content-Type': 'application/json' },
    ...(data === undefined ? {} : { body: JSON.stringify(data) }),
  })
  const body = await response.json().catch(() => null)
  if (!response.ok || !body || (body.code !== undefined && body.code !== 0)) {
    const code = body?.msg || body?.error || body?.code
    const errors = {
      INTENT_PHRASE_CONFLICT: 'conflict',
      INTENT_DRAFT_CHANGED: 'draftChanged',
      INTENT_INVALID_TIMEZONE: 'invalidTimezone',
      INTENT_INVALID_RULE: 'invalidRule',
      INTENT_INVALID_PHRASE: 'invalidRule',
      INTENT_INVALID_POLICY: 'invalidRule',
    }
    throw new Error(t(`esp32Intent.${errors[code] || 'failed'}`))
  }
  return body.data ?? body
}

const requestAgent = (agent, path = '', method = 'GET', data) =>
  request(`/agent/${encodeURIComponent(agent)}/esp32-intents${path}`, method, data)

function selectAgent(id) {
  return router.replace({ name: 'Esp32Intents', query: { agentId: id } })
}

onMounted(async () => {
  try {
    const list = await request('/agent/list')
    agents.value = list.map(agent => ({ id: String(agent.id), agentName: agent.agentName || String(agent.id) }))
    if (!agentId.value && agents.value.length === 1) await selectAgent(agents.value[0].id)
  } catch (cause) {
    agentsError.value = cause.message
  } finally {
    agentsLoading.value = false
  }
})

function applyView(data) {
  if (!data.draft || !data.published) throw new Error(t('esp32Intent.failed'))
  view.value = data
  baseline.value = JSON.stringify(data.draft)
  policy.value = JSON.parse(baseline.value)
  result.value = null
}

async function load() {
  const generation = ++loadGeneration
  busy.value = true
  view.value = null
  error.value = ''
  editing.value = false
  search.value = ''
  scope.value = 'draft'
  try {
    if (!agentId.value) return
    const data = await requestAgent(agentId.value)
    if (generation === loadGeneration) applyView(data)
  } catch (cause) {
    if (generation === loadGeneration) error.value = cause.message
  } finally {
    if (generation === loadGeneration) busy.value = false
  }
}

watch(agentId, load, { immediate: true })
watch([scope, utterance, dirty, () => displayPolicy.value?.timezone], () => { result.value = null })

async function canLeaveDraft() {
  if (!dirty.value && !editing.value) return true
  try {
    await ElMessageBox.confirm(t('esp32Intent.discardConfirm'), t('esp32Intent.title'), { type: 'warning' })
    return true
  } catch {
    return false
  }
}

onBeforeRouteLeave(canLeaveDraft)
onBeforeRouteUpdate((to, from) => to.query.agentId === from.query.agentId || canLeaveDraft())

async function reload() {
  if (await canLeaveDraft()) await load()
}

async function perform(operation, apply) {
  const generation = loadGeneration
  const agent = agentId.value
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const value = await operation(agent)
    if (generation === loadGeneration) apply(value)
  } catch (cause) {
    if (generation === loadGeneration) error.value = cause.message
  } finally {
    if (generation === loadGeneration) busy.value = false
  }
}

async function mutate(action, method, body) {
  await perform(agent => requestAgent(agent, `/${action}`, method, body), data => {
    applyView(data)
    ElMessage.success(t('esp32Intent.done'))
  })
}

async function confirmMutation(action) {
  const agent = agentId.value
  const expectedRevision = revision.value
  try {
    await ElMessageBox.confirm(t(`esp32Intent.${action}Confirm`), t(`esp32Intent.${action}`), { type: 'warning' })
  } catch {
    return
  }
  if (agent === agentId.value && expectedRevision === revision.value && !dirty.value) {
    await mutate(action, 'POST', { revision: expectedRevision })
  }
}

async function preview() {
  result.value = null
  await perform(agent => requestAgent(agent, '/preview', 'POST', { policy: displayPolicy.value, text: utterance.value }),
    data => { result.value = data })
}

function editRule(index = -1) {
  editingIndex.value = index
  editorError.value = ''
  const rule = policy.value.rules[index]
  editor.value = rule ? { ...rule, phraseText: rule.phrases.join('\n') }
    : { id: crypto.randomUUID(), name: '', phraseText: '', action: 'reply', reply: '', enabled: true }
  editing.value = true
}

function applyEdit() {
  const { phraseText, ...rule } = editor.value
  const phrases = phraseText.split(/\r?\n/).map(value => value.trim()).filter(Boolean)
  if (!rule.name || !phrases.length || phrases.length > 16 || phrases.some(value => value.length > 64)
      || (rule.action === 'reply' && !rule.reply.trim())) {
    editorError.value = t('esp32Intent.invalidRule')
    return
  }
  rule.phrases = phrases
  if (rule.action !== 'reply') rule.reply = ''
  if (editingIndex.value < 0) policy.value.rules.push(rule)
  else policy.value.rules.splice(editingIndex.value, 1, rule)
  result.value = null
  editing.value = false
}

function removeRule() {
  policy.value.rules.splice(editingIndex.value, 1)
  result.value = null
  editing.value = false
}
</script>

<style scoped>
.admin-page.intent-page { display: grid; gap: 16px; width: 100%; height: auto; }
.operation-bar, .section-header, .rule-row, .row-actions, .actions, .agent-controls, .rule-tools { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.operation-bar, .section-header, .rule-row { justify-content: space-between; }
h3 { margin: 0 0 8px; font-size: var(--admin-font-md); }
.intro, .rule-body p { margin: 6px 0; color: var(--el-text-color-secondary); line-height: 1.5; }
.rule-row { border-bottom: 1px solid var(--el-border-color-lighter); padding: 12px 0; }
.rule-body { flex: 1 1 260px; min-width: 0; overflow-wrap: anywhere; }
.rule-body p { font-size: var(--admin-font-base); }
.agent-controls, .rule-tools { max-width: 100%; }
.agent-controls .el-select { width: 200px; max-width: 100%; }
.rule-tools .el-input { width: 230px; max-width: 100%; }
.rule-tools { margin: 14px 0; }
.phrase-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.phrase-list .el-tag { height: auto; min-height: 22px; max-width: 100%; }
.phrase-list :deep(.el-tag__content) { white-space: normal; overflow-wrap: anywhere; }
.scope-hint, .timezone-summary { margin-top: 12px; }
.retry { justify-self: start; }
.timezone { max-width: 280px; margin-top: 16px; }
.preview-result { margin-top: 14px; padding: 12px; background: var(--el-fill-color-light); border-radius: 6px; overflow-wrap: anywhere; }
.preview-result p { margin: 6px 0 0; }
.actions { margin-top: 10px; }
.actions .el-button + .el-button, .row-actions .el-button + .el-button { margin-left: 0; }
</style>
