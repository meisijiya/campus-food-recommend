<script setup lang="ts">
/**
 * HitTierBadge.vue
 * ---------------------------------------------------------------------------
 * 推荐命中层角标(api-contract.md §6.4)。
 * 3 tier:mock / dashscope / fallback,颜色对应 tokens.css §hit_tier。
 * ---------------------------------------------------------------------------
 */
import { computed } from "vue";
import type { HitTier } from "@/types/api";

interface Props {
  tier: HitTier;
}

const props = defineProps<Props>();

const tierConfig: Record<HitTier, { label: string; bg: string; text: string }> = {
  mock:      { label: "mock",      bg: "var(--hit-tier-mock-bg)",      text: "var(--hit-tier-mock-text)" },
  dashscope: { label: "dashscope", bg: "var(--hit-tier-dashscope-bg)", text: "var(--hit-tier-dashscope-text)" },
  fallback:  { label: "fallback",  bg: "var(--hit-tier-fallback-bg)",  text: "var(--hit-tier-fallback-text)" },
};

const cfg = computed(() => tierConfig[props.tier]);
</script>

<template>
  <span
    class="inline-flex items-center px-2 py-1 rounded-sm text-xs font-medium"
    :style="{ background: cfg.bg, color: cfg.text }"
    :aria-label="`推荐命中层 ${cfg.label}`"
  >
    {{ cfg.label }}
  </span>
</template>