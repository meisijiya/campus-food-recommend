<script setup lang="ts">
/**
 * AdminView · placeholder(F-16.1)
 * 完整 Admin 折叠 3 sub-tab(Preheat / FeatureFlag / RateLimit)留作 F-16.4。
 * 本 view 演示语义色(success / warning / error / info)+ 状态标签 token。
 */
const semantic = [
  { token: "success",  bg: "var(--color-success-500)", fg: "#FFFFFF", label: "成功 / 命中 mock" },
  { token: "warning",  bg: "var(--color-warning-500)", fg: "#FFFFFF", label: "警告 / 命中 fallback" },
  { token: "error",    bg: "var(--color-error-500)",   fg: "#FFFFFF", label: "错误 / 429 / 限流拒绝" },
  { token: "info",     bg: "var(--color-info-500)",    fg: "#FFFFFF", label: "信息 / 限流提示" },
];

const hitTier = [
  { tier: "mock",      bg: "var(--hit-tier-mock-bg)",      fg: "var(--hit-tier-mock-text)",      label: "MockChatModel 一次过" },
  { tier: "dashscope", bg: "var(--hit-tier-dashscope-bg)", fg: "var(--hit-tier-dashscope-text)", label: "百炼真实 API 通过" },
  { tier: "fallback",  bg: "var(--hit-tier-fallback-bg)",  fg: "var(--hit-tier-fallback-text)",  label: "RBFA 兜底" },
];
</script>

<template>
  <div class="max-w-content mx-auto p-6 space-y-6">
    <h1 class="text-2xl font-semibold">AdminView</h1>

    <div class="placeholder-banner" role="status">
      F-16.1 placeholder · Admin 折叠 3 sub-tab(Preheat / FeatureFlag / RateLimit)留作 F-16.4。
    </div>

    <!-- 语义色演示 === -->
    <section class="space-y-3">
      <h2 class="text-lg font-medium">视觉 token demo · 语义色</h2>
      <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div v-for="s in semantic" :key="s.token" class="space-y-2">
          <div
            :style="{ backgroundColor: s.bg, color: s.fg }"
            class="token-swatch"
          >{{ s.token }}</div>
          <p class="text-xs text-text-secondary">{{ s.label }}</p>
        </div>
      </div>
    </section>

    <!-- hit_tier 三档(ADR-0011 决策 5)== -->
    <section class="space-y-3">
      <h2 class="text-lg font-medium">视觉 token demo · hit_tier 三档(招实习现场卖点)</h2>
      <div class="flex flex-wrap gap-3">
        <span
          v-for="h in hitTier"
          :key="h.tier"
          :style="{ background: h.bg, color: h.fg }"
          class="inline-flex items-center px-2 py-1 rounded-sm text-xs font-medium"
        >
          {{ h.tier }}
        </span>
      </div>
      <p class="text-xs text-text-secondary">推荐结果卡片右上角角标使用,招实习现场一眼可辨"这次推荐命中哪一层"。</p>
    </section>
  </div>
</template>