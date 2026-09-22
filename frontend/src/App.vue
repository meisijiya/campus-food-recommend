<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { useUiStore } from "@/stores/ui";
import ToastHost from "@/components/ToastHost.vue";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const ui = useUiStore();

interface Tab {
  name: "main" | "admin" | "observability";
  path: string;
  label: string;
  adminOnly?: boolean;
}

const allTabs: Tab[] = [
  { name: "main",          path: "/main",          label: "主路径" },
  { name: "admin",         path: "/admin",         label: "管理后台", adminOnly: true },
  { name: "observability", path: "/observability", label: "可观测性" },
];

const tabs = computed(() =>
  allTabs.filter((t) => !t.adminOnly || auth.isAdmin),
);

const activeTab = computed(() => {
  const path = route.path;
  if (path.startsWith("/admin")) return "admin" as const;
  if (path.startsWith("/observability")) return "observability" as const;
  return "main" as const;
});

const hideTabBar = computed(() => route.meta?.hideTabBar === true);

function onLogout() {
  auth.logout();
  ui.toast({ type: "info", message: "已退出登录" });
  router.push({ name: "login" });
}
</script>

<template>
  <div class="min-h-screen flex flex-col bg-bg">
    <!-- Header -->
    <header class="h-14 px-6 flex items-center justify-between border-b border-border bg-surface">
      <div class="flex items-center gap-2 font-semibold text-primary-700">
        <span class="w-6 h-6 rounded-md bg-primary-500 text-text-on-primary inline-flex items-center justify-center text-sm">C</span>
        <span>Campus Food Recommend</span>
      </div>
      <div class="flex items-center gap-3">
        <span
          v-if="auth.isAuthenticated && auth.user"
          class="inline-flex items-center px-2 py-1 rounded-md bg-primary-50 text-primary-700 text-xs font-medium"
          data-testid="user-badge"
        >
          {{ auth.user.username }} · {{ auth.user.role }}
        </span>
        <button
          v-if="auth.isAuthenticated"
          type="button"
          class="text-xs text-text-secondary hover:text-text-primary"
          data-testid="logout-btn"
          @click="onLogout"
        >
          退出
        </button>
        <span class="inline-flex items-center px-2 py-1 rounded-md bg-secondary-50 text-secondary-700 text-xs font-medium">
          F-16.2 · main path
        </span>
      </div>
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
            : 'text-text-secondary hover:bg-bg hover:text-text-primary',
        ]"
        @click="router.push(t.path)"
      >
        {{ t.label }}
      </button>
    </nav>

    <!-- Router outlet -->
    <main class="flex-1">
      <router-view />
    </main>

    <!-- Footer -->
    <footer class="px-6 py-3 border-t border-border text-center text-xs text-text-secondary">
      F-16.2 中等企业级 · ADR-0013 · Vite 5 + Vue 3.5 + Pinia 2.2 + Vitest 2
    </footer>

    <!-- Global toast host -->
    <ToastHost :toasts="ui.toasts" @dismiss="ui.dismiss" />
  </div>
</template>