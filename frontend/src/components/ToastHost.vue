<script setup lang="ts">
/**
 * ToastHost.vue
 * ---------------------------------------------------------------------------
 * 全局 toast 队列渲染器。挂到 App.vue,与 useUiStore 双向绑定。
 * ---------------------------------------------------------------------------
 */
import type { Toast } from "@/types/api";

interface Props {
  toasts: Toast[];
}

defineProps<Props>();

const emit = defineEmits<{
  (e: "dismiss", id: string): void;
}>();

function toastClasses(t: Toast): string {
  switch (t.type) {
    case "success":
      return "bg-success-500 text-white border-success-500";
    case "warning":
      return "bg-warning-500 text-white border-warning-500";
    case "error":
      return "bg-error-500 text-white border-error-500";
    case "info":
      return "bg-info-500 text-white border-info-500";
  }
}
</script>

<template>
  <div
    class="fixed top-4 right-4 z-50 flex flex-col gap-2 max-w-sm"
    role="region"
    aria-label="通知"
  >
    <transition-group name="toast">
      <div
        v-for="t in toasts"
        :key="t.id"
        :class="[
          'px-4 py-2 rounded-md border text-sm shadow-md flex items-start gap-2',
          toastClasses(t),
        ]"
        role="status"
      >
        <span class="flex-1">{{ t.message }}</span>
        <button
          type="button"
          class="text-current opacity-70 hover:opacity-100"
          aria-label="关闭通知"
          @click="emit('dismiss', t.id)"
        >
          ×
        </button>
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>