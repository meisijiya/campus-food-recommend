<script setup lang="ts">
/**
 * AdminView · F-16.2 Admin 容器(嵌套 3 sub-tab)
 * 完整功能留作 F-16.4(per ADR-0013 D3)。
 * 这里只提供 sub-tab 切换 + nested router-view。
 */
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";

const route = useRoute();
const router = useRouter();

const tabs = [
  { name: "admin-preheat",      label: "Preheat" },
  { name: "admin-feature-flag", label: "FeatureFlag" },
  { name: "admin-rate-limit",   label: "RateLimit" },
] as const;

const activeName = computed(() => String(route.name ?? ""));
</script>

<template>
  <div class="max-w-content mx-auto p-6 space-y-4">
    <h1 class="text-2xl font-semibold">Admin</h1>
    <nav class="flex items-center gap-2 border-b border-border" aria-label="管理后台子页">
      <button
        v-for="t in tabs"
        :key="t.name"
        type="button"
        :class="[
          'px-3 py-2 text-sm border-b-2',
          activeName === t.name
            ? 'border-primary-500 text-primary-700 font-medium'
            : 'border-transparent text-text-secondary hover:text-text-primary',
        ]"
        :data-testid="`admin-tab-${t.name}`"
        @click="router.push({ name: t.name })"
      >
        {{ t.label }}
      </button>
    </nav>
    <router-view />
    <p class="text-xs text-text-secondary">
      提示:F-16.2 仅做路由骨架;Preheat / FeatureFlag / RateLimit 子页完整功能在 F-16.4 实施(per ADR-0013 D3)。
    </p>
  </div>
</template>