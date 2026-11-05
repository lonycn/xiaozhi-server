<template>
  <section class="device-debug-tool tool-section" :aria-label="$t('deviceDebug.title')">
    <header><h3>{{ $t('deviceDebug.title') }}</h3><button type="button" class="refresh-target" :disabled="checking || !hasTarget" @click="refreshTarget">{{ $t('deviceDebug.refresh') }}</button></header>
    <p class="debug-hint">{{ $t('deviceDebug.hint') }}</p>
    <p class="device-input-availability" :class="{ online: target?.available }" role="status">{{ $t(availabilityKey) }}</p>
    <form @submit.prevent="send">
      <label class="sr-only" :for="inputId">{{ $t('deviceDebug.title') }}</label>
      <textarea :id="inputId" v-model="draft" :placeholder="$t('deviceDebug.placeholder')" maxlength="2000" rows="4" :disabled="!target?.available || busy" @keydown="inputKeydown" />
      <div class="debug-submit"><small>{{ draft.length }}/2000</small><button type="submit" :disabled="!target?.available || busy || !draft.trim()">{{ $t(sending ? 'deviceDebug.sending' : submitError ? 'deviceDebug.retry' : 'deviceDebug.send') }}</button></div>
    </form>
    <p class="debug-privacy">{{ $t('deviceDebug.privacy') }}</p>
    <div v-if="commandState || submitError" class="device-input-result" role="status">
      <p>{{ $t(submitError ? 'deviceDebug.submitFailed' : 'deviceDebug.' + commandState) }}</p>
      <button v-if="commandId && (commandState === 'unknown' || pollError)" type="button" :disabled="checkingCommand" @click="pollCommand">{{ $t('deviceDebug.retryStatus') }}</button>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue';
import { getServiceUrl } from '../apis/serviceUrl';
import { authenticatedFetch } from '../apis/httpRequest';
import { createDeviceDebugClient } from '../apis/module/deviceDebugClient.mjs';
const props = defineProps({ agentId: String, macAddress: String, token: String, identity: String, client: Object });
const emit = defineEmits(['submitted']);
const api = props.client || createDeviceDebugClient({ baseUrl: getServiceUrl() || '/xiaozhi', getToken: () => props.token, fetchImpl: authenticatedFetch });
const inputId = useId(), draft = ref(''), target = ref(null), checking = ref(false), targetError = ref('');
const sending = ref(false), commandId = ref(''), commandState = ref(''), submitError = ref(false), pollError = ref(false), checkingCommand = ref(false);
const hasTarget = computed(() => !!props.agentId && !!props.macAddress && !!props.token);
const busy = computed(() => sending.value || ['pending', 'dispatched'].includes(commandState.value));
const availabilityKey = computed(() => !hasTarget.value ? 'deviceDebug.noTarget' : checking.value && !target.value ? 'deviceDebug.checking' : targetError.value ? 'deviceDebug.' + targetError.value : target.value?.available ? 'deviceDebug.online' : ({ device_offline: 'deviceDebug.offline', capability_unavailable: 'deviceDebug.unsupported', session_unavailable: 'deviceDebug.sessionUnavailable' })[target.value?.reason] || 'deviceDebug.unavailable');
let epoch = 0, targetVersion = 0, targetController, commandController, targetTimer, commandTimer, pending, submittedText = '', commandDeadline = 0, announced = '', commandDestination = null;
function current(version) { return version === epoch; }
async function refreshTarget() {
  const version = epoch, requestVersion = ++targetVersion;
  targetController?.abort(); clearTimeout(targetTimer);
  if (!hasTarget.value) return;
  const controller = new AbortController(); targetController = controller; checking.value = true;
  try {
    const result = await api.target(props.agentId, props.macAddress, controller.signal);
    if (!current(version) || requestVersion !== targetVersion || controller.signal.aborted) return;
    target.value = result; targetError.value = '';
  } catch (error) {
    if (!current(version) || requestVersion !== targetVersion || controller.signal.aborted) return;
    target.value = null; targetError.value = error.status === 404 && error.code !== 'device_unavailable' ? 'unsupported' : 'unavailable';
  } finally {
    if (current(version) && requestVersion === targetVersion) { checking.value = false; targetTimer = setTimeout(refreshTarget, 10000); }
  }
}
async function send() {
  if (!target.value?.available || busy.value || !draft.value.trim()) return;
  const version = epoch, destination = { ...target.value }, text = draft.value.trim();
  const signature = JSON.stringify([destination.deviceId, destination.conversationId, text]);
  if (pending?.signature !== signature) pending = { signature, payload: { conversationId: destination.conversationId, text, idempotencyKey: crypto.randomUUID() } };
  commandController?.abort(); clearTimeout(commandTimer);
  const controller = new AbortController(); commandController = controller;
  commandDestination = destination;
  sending.value = true; submitError.value = false; commandState.value = ''; commandId.value = ''; pollError.value = false; submittedText = text;
  try {
    const result = await api.submit(destination.deviceId, pending.payload, controller.signal);
    if (!current(version) || controller.signal.aborted) return;
    if (typeof result?.id !== 'string' || !result.id) throw new Error('INVALID_DEVICE_RECEIPT');
    commandId.value = result.id; commandState.value = result.state;
    commandDeadline = Date.now() + 40000;
    await applyCommand(result, destination, version);
  } catch (error) {
    if (!current(version) || controller.signal.aborted) return;
    submitError.value = true;
    if (['session_changed', 'device_offline', 'stale_connection', 'capability_unavailable'].includes(error.code)) void refreshTarget();
  } finally { if (current(version)) sending.value = false; }
}
async function applyCommand(result, destination, version) {
  if (!current(version)) return;
  const valid = ['pending', 'dispatched', 'succeeded', 'failed', 'unknown', 'timed_out', 'canceled'];
  commandState.value = valid.includes(result?.state) ? result.state : 'unknown';
  if (['pending', 'dispatched'].includes(commandState.value) && Date.now() >= commandDeadline) commandState.value = 'unknown';
  if (['pending', 'dispatched'].includes(commandState.value)) {
    commandTimer = setTimeout(pollCommand, 1000); return;
  }
  pending = null;
  if (commandState.value === 'succeeded' && announced !== commandId.value) {
    announced = commandId.value;
    if (draft.value.trim() === submittedText) draft.value = '';
    emit('submitted', { conversationId: destination.conversationId, deviceId: destination.deviceId, commandId: commandId.value });
  }
}
async function pollCommand() {
  if (!commandId.value || checkingCommand.value) return;
  clearTimeout(commandTimer);
  const version = epoch, id = commandId.value, destination = commandDestination, controller = new AbortController();
  commandController?.abort(); commandController = controller; checkingCommand.value = true;
  try {
    const result = await api.command(id, controller.signal);
    if (!current(version) || controller.signal.aborted || id !== commandId.value) return;
    pollError.value = false; await applyCommand(result, destination, version);
  } catch (error) {
    if (!current(version) || controller.signal.aborted) return;
    pollError.value = true;
    if (Date.now() < commandDeadline && busy.value) commandTimer = setTimeout(pollCommand, 1500);
    else commandState.value = 'unknown';
  } finally { if (current(version)) checkingCommand.value = false; }
}
function inputKeydown(event) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) { event.preventDefault(); void send(); }
}
function reset() {
  ++epoch; ++targetVersion; targetController?.abort(); commandController?.abort(); clearTimeout(targetTimer); clearTimeout(commandTimer);
  draft.value = ''; target.value = null; targetError.value = ''; checking.value = false; sending.value = false;
  commandId.value = ''; commandState.value = ''; submitError.value = false; pollError.value = false; checkingCommand.value = false;
  pending = null; submittedText = ''; announced = ''; commandDestination = null;
}
watch([() => props.agentId, () => props.macAddress, () => props.token, () => props.identity], () => { reset(); void refreshTarget(); }, { immediate: true, flush: 'sync' });
onBeforeUnmount(reset);
</script>

<style scoped>
.device-debug-tool{padding-bottom:18px;margin-bottom:18px;border-bottom:1px solid var(--admin-border)}
header{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:12px}h3{font-size:var(--admin-font-md);margin:0}
button,textarea{font:inherit}.refresh-target,.device-input-result button{border:0;background:transparent;padding:3px 0;color:var(--el-color-primary);font-size:var(--admin-font-xs);cursor:pointer}
.debug-hint,.debug-privacy,.device-input-availability,.device-input-result{font-size:var(--admin-font-xs);line-height:1.7}.debug-hint{margin:0 0 10px;color:var(--el-text-color-regular)}.debug-privacy{color:var(--el-text-color-secondary);margin:10px 0 0}.device-input-availability{margin:0 0 10px;color:var(--el-text-color-secondary)}.device-input-availability.online{color:var(--el-color-success)}
textarea{box-sizing:border-box;display:block;resize:vertical;min-height:90px;width:100%;border:1px solid var(--el-border-color);border-radius:6px;padding:10px;color:var(--el-text-color-primary);background:var(--el-bg-color);font-size:var(--admin-font-base);line-height:1.7}textarea:disabled{background:var(--el-fill-color-light);color:var(--el-text-color-disabled);resize:none}
.debug-submit{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:10px}.debug-submit small{font-size:var(--admin-font-xs);color:var(--el-text-color-secondary)}.debug-submit button{border:1px solid var(--el-color-primary);border-radius:5px;background:var(--el-color-primary);color:white;padding:8px 12px;font-size:var(--admin-font-xs);cursor:pointer}button:disabled{opacity:.5;cursor:not-allowed}
.device-input-result{margin-top:12px;padding:10px;border-radius:6px;background:var(--el-fill-color-light);overflow-wrap:anywhere}.device-input-result p{margin:0}
button:focus-visible,textarea:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}.sr-only{position:absolute;width:1px;height:1px;padding:0;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
</style>
