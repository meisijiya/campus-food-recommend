/**
 * stores/ui.ts
 * ---------------------------------------------------------------------------
 * 全局 UI 状态:toast 队列。
 * setup store 模式(ref / computed / function),$reset 自实现。
 * Pinia 模式来源:https://pinia.vuejs.org/core-concepts/#setup-stores
 * ---------------------------------------------------------------------------
 */
import { computed, ref } from "vue";
import { defineStore } from "pinia";
import type { Toast, ToastType } from "@/types/api";

let toastSeq = 0;

export const useUiStore = defineStore("ui", () => {
  // state
  const toasts = ref<Toast[]>([]);

  // getter
  const activeCount = computed(() => toasts.value.length);

  // actions
  function toast(input: { type: ToastType; message: string; duration?: number }) {
    const id = `t${++toastSeq}`;
    const duration = input.duration ?? 3500;
    const t: Toast = { id, type: input.type, message: input.message, duration };
    toasts.value.push(t);
    if (duration > 0) {
      window.setTimeout(() => dismiss(id), duration);
    }
    return id;
  }

  function dismiss(id: string) {
    toasts.value = toasts.value.filter((x) => x.id !== id);
  }

  function clear() {
    toasts.value = [];
  }

  // setup store 必须自实现 $reset(Pinia 文档 §state.html#resetting-the-state)
  function $reset() {
    toasts.value = [];
  }

  return { toasts, activeCount, toast, dismiss, clear, $reset };
});