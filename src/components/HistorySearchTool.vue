<template>
  <section class="tool-section history-search-tool">
    <h3>{{ $t('home.chatHistory') }}</h3>
    <button type="button" class="history-search-entry" :disabled="disabled" @click="$emit('open')"><Search aria-hidden="true" />{{ $t('historyFinder.title') }}</button>
  </section>
</template>

<script setup>
import { Search } from '@element-plus/icons-vue';
defineProps({ disabled: Boolean });
defineEmits(['open']);
</script>

<style scoped>
.history-search-tool{padding-bottom:18px;margin-bottom:18px;border-bottom:1px solid var(--admin-border, #e5e9f0)}
h3{font-size:var(--admin-font-md);margin:0 0 12px}
.history-search-entry{display:flex;align-items:center;justify-content:center;gap:6px;width:100%;padding:10px 12px;border:1px solid var(--el-color-primary-light-7);border-radius:6px;background:var(--el-color-primary-light-9);color:var(--el-color-primary);font:inherit;font-size:var(--admin-font-xs);cursor:pointer}
.history-search-entry svg{width:16px;height:16px;flex-shrink:0}
.history-search-entry:disabled{color:var(--el-text-color-disabled);cursor:not-allowed}
.history-search-entry:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}
</style>
