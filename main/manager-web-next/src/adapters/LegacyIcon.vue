<script setup>
import { computed, useAttrs, normalizeClass } from 'vue';
import { ElIcon } from 'element-plus';
import { resolveLegacyIcon } from './icons';
defineOptions({ inheritAttrs: false });
const attrs = useAttrs();
const icon = computed(() => resolveLegacyIcon(normalizeClass(attrs.class)));
const spinning = computed(() => normalizeClass(attrs.class).includes('el-icon-loading'));
</script>

<template><ElIcon v-bind="attrs" :class="{ 'is-loading': spinning }" aria-hidden="true"><component :is="icon" /></ElIcon></template>
