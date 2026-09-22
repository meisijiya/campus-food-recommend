<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";

const route = useRoute();
const router = useRouter();

const tabs = [
  { name: "main",          path: "/main",          label: "主路径" },
  { name: "admin",         path: "/admin",         label: "管理后台" },
  { name: "observability", path: "/observability", label: "可观测性" },
] as const;

const activeTab = computed(() => {
  const path = route.path;
  if (path.startsWith("/admin"))       return "admin";
  if (path.startsWith("/observability")) return "observability";
  return "main";
});

const hideTabBar = computed(() => route.meta?.hideTabBar === true);
</script>

<template>
  <div class="min-h-screen flex flex-col bg-bg">
    <!-- Header -->
    <header class="h-14 px-6 flex items-center justify-between border-b border-border bg-surface">
      <div class="flex items-center gap-2 font-semibold text-primary-700">
        <span class="w-6 h-6 rounded-md bg-primary-500 text-text-on-primary inline-flex items-center justify-center text-sm">C</span>
        <span>Campus Food Recommend</span>
      </div>
      <span class="inline-flex items-center px-2 py-1 rounded-md bg-secondary-50 text-secondary-700 text-xs font-medium">
        F-16.1 · skeleton
      </span>
    </header>

    <!-- Tab bar(隐藏于 login 页)== -->
    <nav
      v-if="!hideTabBar"
      class="h-14 px-6 flex items-center gap-1 border-b border-border bg-surface"
      role="tablist"
      aria-label="主导航"
    >
      <button
        v-for="t in tabs"
        :key="t.name"
        role="tab"
        :aria-selected="activeTab === t.name"
        :class="[
          'h-10 px-4 rounded-md text-sm font-medium transition-colors',
          activeTab === t.name
            ? 'bg-primary-50 text-primary-700'
            : 'text-text-secondary hover:bg-bg hover:text-text-primary'
        ]"
        @click="router.push(t.path)"
      >
        {{ t.label }}
      </button>
    </nav>

    <!-- Router outlet -->
    <main class="flex-1">
      <router-view />
      <!-- 占位占位占位 -->
      <span class="sr-only">当前路径:{{ route.fullPath }}</span>
    </main>

    <!-- Footer -->
    <footer class="px-6 py-3 border-t border-border text-center text-xs text-text-secondary">
      F-16.1 骨架 · ADR-0011 + ADR-0012 · Vite 5 + Vue 3.5 + Tailwind 3.4
    </footer>
  </div>
</template>