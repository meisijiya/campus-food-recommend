<script setup lang="ts">
/**
 * LoginView · placeholder(F-16.1)
 * 完整登录逻辑(login form + JWT 提交 + 演示账号提示)留作 F-16.2。
 * 本 view 演示视觉 token:色板 / 字号 / 圆角 / 间距,作为招实习现场 README 截图素材。
 */
const colorSwatches = [
  { name: "primary-500",   bg: "#3D8B5F", fg: "#FFFFFF", label: "校园绿" },
  { name: "secondary-500", bg: "#E0A458", fg: "#FFFFFF", label: "暖橙" },
  { name: "accent-500",    bg: "#C75450", fg: "#FFFFFF", label: "校园红" },
  { name: "bg",            bg: "#FAF7F2", fg: "#2C2A26", label: "暖白底" },
  { name: "text-primary",  bg: "#2C2A26", fg: "#FFFFFF", label: "暖深灰" },
];

const fontSizes = [
  { token: "text-xs",   px: 12 },
  { token: "text-sm",   px: 14 },
  { token: "text-base", px: 16 },
  { token: "text-lg",   px: 18 },
  { token: "text-xl",   px: 20 },
  { token: "text-2xl",  px: 24 },
  { token: "text-3xl",  px: 30 },
];
</script>

<template>
  <div class="max-w-content mx-auto p-6 space-y-6">
    <h1 class="text-2xl font-semibold">LoginView</h1>

    <div class="placeholder-banner" role="status">
      F-16.1 placeholder · 此页面完整登录逻辑留作 F-16.2(含 JWT 拦截器 + 演示账号提示卡)。
    </div>

    <!-- 色板演示 === -->
    <section class="space-y-3">
      <h2 class="text-lg font-medium">视觉 token demo · 色板</h2>
      <div class="grid grid-cols-2 md:grid-cols-5 gap-3">
        <div v-for="c in colorSwatches" :key="c.name" class="space-y-2">
          <div :style="{ backgroundColor: c.bg, color: c.fg }" class="token-swatch">{{ c.name }}</div>
          <p class="text-xs text-text-secondary">{{ c.label }}<br /><code class="font-mono">{{ c.bg }}</code></p>
        </div>
      </div>
    </section>

    <!-- 字号演示 === -->
    <section class="space-y-3">
      <h2 class="text-lg font-medium">视觉 token demo · 字号</h2>
      <div class="token-card space-y-2">
        <div v-for="f in fontSizes" :key="f.token" class="flex items-baseline gap-4">
          <span class="font-mono text-xs text-text-secondary w-24">{{ f.token }} ({{ f.px }}px)</span>
          <span :class="f.token" class="text-text-primary">校园美食推荐 demo</span>
        </div>
      </div>
    </section>
  </div>
</template>