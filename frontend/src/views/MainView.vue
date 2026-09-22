<script setup lang="ts">
/**
 * MainView · F-16.2 主路径布局容器
 * ---------------------------------------------------------------------------
 * - 自动 init session(若 stage === INIT)
 * - 顶部 step 指示器(zone → cuisine → recommend)
 * - <router-view> 渲染当前 stage 子路由
 * ---------------------------------------------------------------------------
 */
import { onMounted, computed } from "vue";
import { useRouter } from "vue-router";
import { useSessionStore } from "@/stores/session";

const session = useSessionStore();
const router = useRouter();

const steps = [
  { key: "ZONE",     label: "1 · 选商圈" },
  { key: "CUISINE",  label: "2 · 选菜系" },
  { key: "MERCHANT", label: "3 · 推荐结果" },
] as const;

const currentStepIdx = computed(() => {
  switch (session.stage) {
    case "INIT": return -1;
    case "ZONE": return 0;
    case "CUISINE": return 1;
    case "MERCHANT": return 2;
    default: return -1;
  }
});

onMounted(async () => {
  if (session.stage === "INIT") {
    try {
      await session.init();
      // 默认跳到选商圈
      if (router.currentRoute.value.name === "main-zone") {
        // stay
      }
    } catch {
      // 拦截器已 toast,这里静默
    }
  }
});

function gotoStep(idx: number) {
  if (idx === 0) router.push({ name: "main-zone" });
  else if (idx === 1) router.push({ name: "main-cuisine" });
  else if (idx === 2) router.push({ name: "main-recommend" });
}
</script>

<template>
  <div class="max-w-content mx-auto p-6 space-y-4">
    <!-- Step indicator -->
    <ol
      class="flex items-center gap-2 text-sm"
      aria-label="主路径步骤"
      data-testid="step-indicator"
    >
      <li
        v-for="(s, idx) in steps"
        :key="s.key"
        :class="[
          'px-3 py-1 rounded-md border',
          currentStepIdx === idx
            ? 'bg-primary-500 text-white border-primary-500'
            : currentStepIdx > idx
            ? 'bg-primary-50 text-primary-700 border-primary-100 cursor-pointer hover:bg-primary-100'
            : 'bg-surface text-text-disabled border-border',
        ]"
        :data-testid="`step-${idx}`"
        :aria-current="currentStepIdx === idx ? 'step' : undefined"
        @click="currentStepIdx > idx && gotoStep(idx)"
      >
        {{ s.label }}
      </li>
    </ol>

    <!-- Nested router outlet -->
    <router-view />
  </div>
</template>