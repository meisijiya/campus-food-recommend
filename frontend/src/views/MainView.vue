<script setup lang="ts">
/**
 * MainView · placeholder(F-16.1)
 * 完整主路径流程(zone → cuisine → recommend → detail + 点赞闭环)留作 F-16.2。
 * 本 view 演示圆角 / 阴影 / 间距 token。
 */
const radii = [
  { token: "radius-sm",   value: "0.25rem", class: "rounded-sm" },
  { token: "radius-md",   value: "0.375rem", class: "rounded-md" },
  { token: "radius-lg",   value: "0.5rem", class: "rounded-lg" },
  { token: "radius-xl",   value: "0.75rem", class: "rounded-xl" },
];

const shadows = [
  { token: "shadow-xs", class: "shadow-xs" },
  { token: "shadow-sm", class: "shadow-sm" },
  { token: "shadow-md", class: "shadow-md" },
  { token: "shadow-lg", class: "shadow-lg" },
];
</script>

<template>
  <div class="max-w-content mx-auto p-6 space-y-6">
    <h1 class="text-2xl font-semibold">MainView</h1>

    <div class="placeholder-banner" role="status">
      F-16.1 placeholder · 主路径四阶段流程(zone → cuisine → recommend → detail)留作 F-16.2。
    </div>

    <!-- 圆角演示 === -->
    <section class="space-y-3">
      <h2 class="text-lg font-medium">视觉 token demo · 圆角</h2>
      <div class="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div v-for="r in radii" :key="r.token" class="space-y-2">
          <div :class="['bg-primary-50', 'p-6', 'border', 'border-border', r.class]">
            <span class="text-sm text-primary-700 font-medium">{{ r.class }}</span>
          </div>
          <p class="text-xs text-text-secondary"><code class="font-mono">{{ r.token }}</code> · {{ r.value }}</p>
        </div>
      </div>
    </section>

    <!-- 阴影演示 === -->
    <section class="space-y-3">
      <h2 class="text-lg font-medium">视觉 token demo · 阴影</h2>
      <div class="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div v-for="s in shadows" :key="s.token" class="space-y-2">
          <div :class="['bg-surface', 'p-6', 'rounded-lg', s.class]">
            <span class="text-sm font-medium">{{ s.class }}</span>
          </div>
          <p class="text-xs text-text-secondary"><code class="font-mono">{{ s.token }}</code></p>
        </div>
      </div>
    </section>
  </div>
</template>