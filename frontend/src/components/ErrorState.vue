<script setup lang="ts">
/**
 * ErrorState.vue
 * ---------------------------------------------------------------------------
 * 加载失败错误态,展示错误信息 + 重试按钮。
 * 用于 zone 列表 / 商家详情 / observability 等的 fetch 失败兜底。
 * ---------------------------------------------------------------------------
 */
interface Props {
  title?: string;
  message: string;
  retryable?: boolean;
}

withDefaults(defineProps<Props>(), {
  title: "加载失败",
  retryable: true,
});

const emit = defineEmits<{
  (e: "retry"): void;
}>();
</script>

<template>
  <div
    class="flex flex-col items-center justify-center gap-3 p-6 rounded-md
           border border-error-500/30 bg-error-500/5"
    role="alert"
  >
    <div class="text-error-500 text-sm font-medium">{{ title }}</div>
    <div class="text-text-secondary text-sm text-center">{{ message }}</div>
    <button
      v-if="retryable"
      type="button"
      class="px-3 py-1.5 text-sm rounded-sm border border-error-500
             text-error-500 hover:bg-error-500/10 transition-colors"
      @click="emit('retry')"
    >
      重试
    </button>
  </div>
</template>