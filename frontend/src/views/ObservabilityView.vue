<script setup lang="ts">
/**
 * ObservabilityView · placeholder(F-16.1)
 * 完整指标页(4 业务 + 6 技术指标 + Recharts + 自动刷新 + ErrorState)留作 F-16.3。
 * 本 view 演示间距 scale + 字号 scale + 列表空态 token。
 */
const spaceScopes = [
  { name: "space-1",  px: 4 },
  { name: "space-2",  px: 8 },
  { name: "space-3",  px: 12 },
  { name: "space-4",  px: 16 },
  { name: "space-6",  px: 24 },
  { name: "space-8",  px: 32 },
];
</script>

<template>
  <div class="max-w-content mx-auto p-6 space-y-6">
    <h1 class="text-2xl font-semibold">ObservabilityView</h1>

    <div class="placeholder-banner" role="status">
      F-16.1 placeholder · 自绘指标页(Recharts + 4 业务 + 6 技术 + 自动刷新)留作 F-16.3。
    </div>

    <!-- 间距 scale 演示 === -->
    <section class="space-y-3">
      <h2 class="text-lg font-medium">视觉 token demo · 间距 scale(4px base)</h2>
      <div class="token-card space-y-2">
        <div v-for="s in spaceScopes" :key="s.name" class="flex items-center gap-4">
          <span class="font-mono text-xs text-text-secondary w-32">{{ s.name }} ({{ s.px }}px)</span>
          <span :style="{ width: s.px + 'px', height: '16px' }" class="bg-primary-500 rounded-sm" />
        </div>
      </div>
    </section>

    <!-- 空态演示 === -->
    <section class="space-y-3">
      <h2 class="text-lg font-medium">视觉 token demo · 空态 / 错误态</h2>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div class="token-card text-center py-12 space-y-3">
          <p class="text-text-primary font-medium">暂无指标数据</p>
          <p class="text-sm text-text-secondary">dev profile 默认不启用 /actuator/prometheus;<br />需 prod profile + Spring Boot Actuator 配置。</p>
        </div>
        <div class="token-card text-center py-12 space-y-3">
          <p class="text-error-500 font-medium">加载失败</p>
          <p class="text-sm text-text-secondary">指标源不可达,点击下方按钮重试。</p>
          <button class="inline-flex items-center px-4 h-10 rounded-md bg-primary-500 text-text-on-primary text-sm font-medium">
            重试
          </button>
        </div>
      </div>
    </section>
  </div>
</template>