<template>
  <section class="support-settings">
    <header class="settings-heading"><div><h1>{{ $t('support.settings') }}</h1><p>{{ $t('support.profileNote') }}</p></div><el-button :disabled="!authorized || loading || saving" @click="reload()">{{ $t('chatHistory.refresh') }}</el-button></header>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <p v-if="!authorized" role="status">{{ $t('support.settingsDenied') }}</p>
    <template v-else>
      <p v-if="saved" class="settings-saved" role="status">{{ $t('support.settingsSaved') }}</p>
      <div v-if="editingAccount" ref="editorCard" class="settings-card profile-editor-card">
        <header class="editor-heading"><h2>{{ $t('support.edit') }} · {{ editingAccount.username }}</h2><el-button :disabled="saving" @click="closeEditor">{{ $t('common.cancel') }}</el-button></header>
        <form class="settings-form" @submit.prevent="saveProfile">
          <label><span>{{ $t('support.role') }}</span><el-select v-model="edit.roles" multiple :aria-label="$t('support.role')" :disabled="saving"><el-option value="support_agent" :label="$t('support.agent')" /><el-option value="expert" :label="$t('support.expert')" /></el-select></label>
          <label><span>{{ $t('support.capacity') }}</span><el-input-number v-model="edit.capacity_text" :min="1" :max="20" :precision="0" :aria-label="$t('support.capacity')" :disabled="saving" /></label>
          <label class="enabled-field"><span>{{ $t('support.enabled') }}</span><el-switch v-model="edit.enabled" :aria-label="$t('support.enabled')" :disabled="saving" /></label>
          <div class="public-profile-fields">
            <h3>{{ $t('support.publicProfile') }}</h3>
            <p class="field-note">{{ $t('support.directoryNote') }}</p>
            <div class="profile-editor">
              <div class="avatar-editor"><el-avatar :size="72" :src="avatarSource(edit.profile.avatar_url)">{{ edit.profile.display_name?.slice(0,1) || '?' }}</el-avatar><label class="avatar-upload"><span>{{ $t('support.uploadAvatar') }}</span><input type="file" accept="image/png,image/jpeg,image/webp" :disabled="saving || avatarLoading" @change="chooseAvatar" /></label><el-button v-if="edit.profile.avatar_url" text :disabled="saving" @click="edit.profile.avatar_url = ''">{{ $t('support.removeAvatar') }}</el-button></div>
              <label><span>{{ $t('support.displayName') }}</span><el-input v-model="edit.profile.display_name" :aria-label="$t('support.displayName')" maxlength="80" :disabled="saving" /></label>
              <label><span>{{ $t('support.expertTitle') }}</span><el-input v-model="edit.profile.title" :aria-label="$t('support.expertTitle')" maxlength="120" :disabled="saving" /></label>
              <label class="wide-field"><span>{{ $t('support.specialties') }}</span><el-input v-model="specialtiesText" :aria-label="$t('support.specialties')" :placeholder="$t('support.specialtiesHint')" :disabled="saving" /></label>
              <label class="wide-field"><span>{{ $t('support.profileBio') }}</span><el-input v-model="edit.profile.bio" type="textarea" :rows="3" :aria-label="$t('support.profileBio')" maxlength="1000" show-word-limit :disabled="saving" /></label>
              <label class="enabled-field"><span>{{ $t('support.listed') }}</span><el-switch v-model="edit.profile.listed" :aria-label="$t('support.listed')" :disabled="saving" /></label>
            </div>
          </div>
          <el-button native-type="submit" type="primary" :loading="saving" :disabled="!validForm || saving || loading">{{ $t('support.save') }}</el-button>
        </form>
      </div>
      <div class="settings-card profile-list" :aria-busy="loading">
        <h2>{{ $t('support.profiles') }}</h2>
        <p v-if="loading" role="status">{{ $t('support.loading') }}</p>
        <el-table :data="agents">
          <el-table-column :label="$t('support.username')" min-width="150"><template #default="{ row }">{{ row.username }} <el-tag v-if="row.account_enabled === false" type="danger" size="small">{{ $t('knowledgeBaseDialog.statusDisabled') }}</el-tag></template></el-table-column>
          <el-table-column :label="$t('support.publicProfile')" min-width="220"><template #default="{ row }"><div class="profile-cell"><el-avatar :size="38" :src="avatarSource(row.profile?.avatar_url)">{{ (row.profile?.display_name || row.username || '?').slice(0,1) }}</el-avatar><div><strong>{{ row.profile?.display_name || row.username }}</strong><small>{{ row.profile?.title || $t('support.profileIncomplete') }}</small></div></div></template></el-table-column>
          <el-table-column :label="$t('support.listed')" min-width="130"><template #default="{ row }">{{ $t(supportRoles(row).includes('expert') && row.enabled && row.account_enabled !== false && row.profile?.listed !== false ? 'support.directoryVisible' : 'support.directoryHidden') }}</template></el-table-column>
          <el-table-column :label="$t('support.role')" min-width="120"><template #default="{ row }">{{ supportRoles(row).map(role => $t(role === 'expert' ? 'support.expert' : 'support.agent')).join(' / ') }}</template></el-table-column>
          <el-table-column :label="$t('support.enabled')" min-width="110"><template #default="{ row }">{{ $t(row.enabled ? 'knowledgeBaseDialog.statusEnabled' : 'knowledgeBaseDialog.statusDisabled') }}</template></el-table-column>
          <el-table-column prop="capacity_text" :label="$t('support.capacity')" min-width="110" />
          <el-table-column :label="$t('support.edit')" width="100" fixed="right"><template #default="{ row }"><el-button :disabled="saving" @click="editProfile(row)">{{ $t('support.edit') }}</el-button></template></el-table-column>
        </el-table>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useAuthStore } from '../stores/auth';
import supportApi from '../apis/module/support';
import { isSupportAdmin, supportRoles, cursorInteger } from '../utils/support.mjs';
import i18n from '../i18n';
import { getServiceUrl } from '../apis/serviceUrl';

const auth = useAuthStore();
const emptyProfile = () => ({ display_name: '', avatar_url: '', title: '', bio: '', specialties: [], listed: true });
const avatarLoading = ref(false);
const editingAccount = ref(null), editorCard = ref(null);
const emptyEdit = () => ({ role: 'support_agent', roles: ['support_agent'], enabled: true, capacity_text: 1, profile: emptyProfile() });
const agents = ref([]), edit = ref(emptyEdit()), loading = ref(false), saving = ref(false), error = ref(''), saved = ref(false), blocked = ref(false);
const identity = computed(() => JSON.stringify([auth.bearer, auth.userInfo.id || '', auth.userInfo.superAdmin]));
const authorized = computed(() => !!auth.bearer && isSupportAdmin(auth.userInfo) && !/^xext_/i.test(auth.bearer) && !blocked.value);
const specialtiesText = computed({ get: () => edit.value.profile.specialties.join('、'), set: value => { edit.value.profile.specialties = [...new Set(value.split(/[,，、;；\n]/).map(s => s.trim()).filter(Boolean))]; } });
const validForm = computed(() => !!editingAccount.value && /^[1-9][0-9]{0,18}$/.test(editingAccount.value.id) && BigInt(editingAccount.value.id) <= 9223372036854775807n && edit.value.roles.length > 0 && edit.value.roles.every(role => ['expert','support_agent'].includes(role)) && typeof edit.value.enabled === 'boolean' && Number.isInteger(edit.value.capacity_text) && edit.value.capacity_text >= 1 && edit.value.capacity_text <= 20 && !avatarLoading.value && edit.value.profile.specialties.length <= 12 && edit.value.profile.specialties.every(s => s.length <= 40));
let readController = null, saveController = null, disposed = false;
function current(controller, key) { return !disposed && !controller.signal.aborted && key === identity.value && authorized.value; }
function fail(controller, key, cause) {
  if (!current(controller, key)) return;
  error.value = cause.message || i18n.t('support.settingsLoadFailed');
  if ([401,403].includes(cause.status)) { blocked.value = true; agents.value = []; resetEditor(); saved.value = false; }
}
async function reload(keepSaved = false) {
  readController?.abort(); agents.value = []; loading.value = false; error.value = ''; if (!keepSaved) { saved.value = false; resetEditor(); }
  if (!authorized.value) return;
  const controller = new AbortController(), key = identity.value; readController = controller; loading.value = true;
  try {
    const rows = new Map(); let cursor = '0';
    while (current(controller, key)) {
      const page = await supportApi.agents(cursor, controller.signal);
      if (!current(controller, key)) return;
      if (!Array.isArray(page?.items)) throw new Error(i18n.t('support.settingsLoadFailed'));
      for (const row of page.items) rows.set(String(row.user_id), row);
      if (!page.has_more) break;
      if (cursorInteger(page.next_cursor) === 0n || (cursor !== '0' && cursorInteger(page.next_cursor) >= cursorInteger(cursor))) throw new Error(i18n.t('support.settingsLoadFailed'));
      cursor = String(page.next_cursor);
    }
    if (current(controller, key)) agents.value = [...rows.values()];
  } catch (cause) { fail(controller, key, cause); }
  finally { if (!disposed && readController === controller) loading.value = false; }
}
function editProfile(row) {
  if (!authorized.value || saving.value || loading.value || !agents.value.some(a => String(a.user_id) === String(row.user_id))) return;
  editingAccount.value = Object.freeze({ id: String(row.user_id), username: row.username });
  edit.value = { role: row.role, roles: supportRoles(row), enabled: !!row.enabled, capacity_text: Number(row.capacity_text), profile: { ...emptyProfile(), ...row.profile, specialties: [...(row.profile?.specialties || [])] } }; saved.value = false; error.value = '';
  nextTick(() => editorCard.value?.scrollIntoView?.({ block: 'start' }));
}
function resetEditor() { editingAccount.value = null; edit.value = emptyEdit(); }
function closeEditor() { if (!saving.value) { resetEditor(); error.value = ''; } }
async function saveProfile() {
  if (!authorized.value || !validForm.value || saving.value || loading.value) return;
  const controller = new AbortController(), key = identity.value; saveController = controller; saving.value = true; error.value = ''; saved.value = false;
  const userId = editingAccount.value.id;
  const { role, roles, enabled, capacity_text, profile } = edit.value;
  const payload = { role, roles, enabled, capacity_text, profile };
  try {
    await supportApi.profile(userId, { ...payload, role: payload.roles.includes(payload.role) ? payload.role : payload.roles[0] }, controller.signal);
    if (!current(controller, key)) return;
    saved.value = true; resetEditor(); await reload(true);
  } catch (cause) { fail(controller, key, cause); }
  finally { if (!disposed && saveController === controller) saving.value = false; }
}
function avatarSource(value = '') { return value.startsWith('/api/v1/support/avatars/') ? `${(getServiceUrl() || '/xiaozhi').replace(/\/$/,'')}${value}` : value; }
async function chooseAvatar(event) {
  const file = event.target.files?.[0]; event.target.value = ''; if (!file || saving.value) return;
  if (!['image/png','image/jpeg','image/webp'].includes(file.type) || file.size > 5 * 1024 * 1024) { error.value = i18n.t('support.avatarInvalid'); return; }
  const key = identity.value, target = edit.value; avatarLoading.value = true; error.value = '';
  let bitmap;
  try {
    bitmap = await createImageBitmap(file);
    const scale = Math.min(1,512 / Math.max(bitmap.width,bitmap.height));
    const canvas = document.createElement('canvas'); canvas.width = Math.max(1,Math.round(bitmap.width * scale)); canvas.height = Math.max(1,Math.round(bitmap.height * scale));
    canvas.getContext('2d').drawImage(bitmap,0,0,canvas.width,canvas.height);
    const data = canvas.toDataURL('image/jpeg',0.85);
    if (data.length > 700000) throw new Error(i18n.t('support.avatarInvalid'));
    if (!disposed && key === identity.value && edit.value === target && authorized.value) edit.value.profile.avatar_url = data;
  } catch { if (!disposed && key === identity.value) error.value = i18n.t('support.avatarInvalid'); }
  finally { bitmap?.close(); avatarLoading.value = false; }
}
watch(identity, () => {
  readController?.abort(); saveController?.abort(); agents.value = []; resetEditor(); loading.value = false; saving.value = false; error.value = ''; saved.value = false; blocked.value = false;
  reload();
}, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { disposed = true; readController?.abort(); saveController?.abort(); });
</script>

<style scoped>
.support-settings{width:100%;margin:0;color:var(--admin-text);text-align:left}.settings-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;margin-bottom:20px}.settings-heading h1{margin:0 0 8px;font-size:var(--admin-font-title)}.settings-heading p{font-size:var(--admin-font-base);line-height:1.7;color:var(--el-text-color-secondary);margin:0;max-width:760px}.settings-heading .el-button{flex-shrink:0}.settings-card{background:var(--el-bg-color);border:1px solid var(--admin-border);border-radius:6px;padding:20px;margin-top:16px}.settings-card h2{font-size:var(--admin-font-md);margin:0 0 18px}.profile-editor-card{scroll-margin-top:110px}.editor-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:18px}.editor-heading h2{margin:0;overflow-wrap:anywhere}.settings-form{display:flex;align-items:flex-end;gap:18px;flex-wrap:wrap}.settings-form label{display:flex;flex-direction:column;gap:8px;font-size:var(--admin-font-base);min-width:0}.settings-form .el-input{width:230px;max-width:100%}.settings-form .el-select{width:170px;max-width:100%}.enabled-field{min-width:90px}.public-profile-fields{width:100%;border-top:1px solid var(--admin-border);padding-top:18px;margin-top:4px}.public-profile-fields h3{margin:0 0 6px;font-size:var(--admin-font-md)}.field-note{color:var(--el-text-color-secondary);font-size:var(--admin-font-xs);line-height:1.7;margin:0 0 18px}.profile-editor{display:grid;grid-template-columns:100px 1fr 1fr;gap:18px}.avatar-editor{grid-row:span 3;display:flex;flex-direction:column;align-items:center;gap:8px}.avatar-upload{color:var(--el-color-primary);cursor:pointer;font-size:var(--admin-font-xs)}.avatar-upload input{display:none}.profile-editor .el-input{width:100%}.profile-editor>.enabled-field{grid-column:2 / -1}.wide-field{grid-column:2 / -1}.profile-cell{display:flex;align-items:center;gap:10px}.profile-cell strong{font-size:var(--admin-font-base);font-weight:500}.profile-cell small{display:block;color:var(--el-text-color-secondary);font-size:var(--admin-font-xs);margin-top:3px}.profile-list{overflow:hidden}.settings-saved{font-size:var(--admin-font-base);color:var(--el-color-success);margin:14px 0 0}@media(max-width:760px){.settings-heading{gap:12px}.settings-heading h1{font-size:var(--admin-font-title)}.settings-card{padding:14px}.profile-editor{grid-template-columns:1fr}.avatar-editor{grid-row:auto;align-items:flex-start}.wide-field,.profile-editor>.enabled-field{grid-column:auto}.settings-form{gap:14px}.settings-form label:not(.enabled-field){width:100%}.settings-form .el-input,.settings-form .el-select{width:100%}}
</style>
