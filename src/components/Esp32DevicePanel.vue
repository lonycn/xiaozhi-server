<template>
  <section v-if="!hidden" class="esp32-panel" aria-label="ESP32 设备状态">
    <div class="esp32-heading"><strong>设备连接</strong><button type="button" :disabled="loading" @click="refresh">刷新</button></div>
    <p v-if="error" role="alert">设备状态暂不可用，请稍后重试。</p>
    <template v-else>
      <p>状态：{{ statusLabel }}</p>
      <p>可用能力：{{ capabilities.length ? capabilities.join('、') : '暂无' }}</p>
      <p>设备会话：{{ sessions.length }} 条</p>
      <button v-if="sessions.length" type="button" @click="$emit('show-history', sessions)">查看会话</button>
    </template>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { getServiceUrl } from '../apis/serviceUrl';
import { createEsp32DeviceClient } from '../apis/module/esp32DeviceClient.mjs';

const props = defineProps({ deviceId: { type: String, required: true }, token: { type: String, required: true }, client: { type: Object, default: null } });
defineEmits(['show-history']);
const api = props.client || createEsp32DeviceClient({ baseUrl: getServiceUrl() });
const hidden = ref(false), loading = ref(false), error = ref(false), status = ref(null), sessions = ref([]);
let controller, timer, sequence = 0, currentDeviceId, currentToken;
const statusLabel = computed(() => ({ online: '在线', offline: '离线', unknown: '未知' })[status.value?.state] || '未知');
const capabilities = computed(() => {
  let value = status.value?.capabilities;
  if (typeof value === 'string') { try { value = JSON.parse(value); } catch { return []; } }
  return Array.isArray(value) ? value.filter(item => typeof item === 'string') : [];
});
async function refresh() {
  const id = props.deviceId, token = props.token, version = ++sequence;
  controller?.abort(); clearTimeout(timer);
  if (id !== currentDeviceId || token !== currentToken) {
    currentDeviceId = id; currentToken = token;
    status.value = null; sessions.value = []; hidden.value = false; error.value = false;
  }
  if (!id || !token) { loading.value = false; return; }
  const localController = new AbortController(); controller = localController; loading.value = true;
  try {
    const result = await api.status(id, token, localController.signal);
    const history = await api.sessions(id, token, localController.signal);
    if (version !== sequence || localController.signal.aborted) return;
    status.value = result; sessions.value = Array.isArray(history) ? history : [];
    error.value = false; hidden.value = false;
  } catch (e) {
    if (version !== sequence || localController.signal.aborted) return;
    if (e.status === 404) hidden.value = true; else error.value = true;
  } finally {
    if (version === sequence) {
      loading.value = false;
      if (!hidden.value) timer = setTimeout(refresh, 10000);
    }
  }
}
watch(() => [props.deviceId, props.token], refresh, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => { sequence++; clearTimeout(timer); controller?.abort(); });
</script>

<style scoped>
.esp32-panel { padding: 14px; border: 1px solid #e1e7ef; border-radius: 6px; color: #34445a; background: #fff; }
.esp32-heading { display: flex; justify-content: space-between; align-items: center; }
.esp32-panel p { margin: 8px 0; font-size: var(--admin-font-base); }
.esp32-panel button { color: var(--el-color-primary, #409eff); background: transparent; border: 0; cursor: pointer; }
</style>
