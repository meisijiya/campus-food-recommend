<script setup lang="ts">
/**
 * TokenCounter.vue
 * ---------------------------------------------------------------------------
 * prompt + completion tokens 显式组件(配合 hit_tier 展示,F-3 卖点)。
 * ---------------------------------------------------------------------------
 */
interface Props {
  prompt: number | null;
  completion: number | null;
}

defineProps<Props>();
</script>

<template>
  <span
    class="inline-flex items-center gap-1 text-xs text-text-secondary"
    :aria-label="`prompt ${prompt ?? 0} tokens, completion ${completion ?? 0} tokens`"
  >
    <span>{{ prompt ?? 0 }}</span>
    <span class="opacity-60">+</span>
    <span>{{ completion ?? 0 }}</span>
    <span class="opacity-60">tokens</span>
  </span>
</template>