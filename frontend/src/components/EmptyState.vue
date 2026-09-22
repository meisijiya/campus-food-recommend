<script setup lang="ts">
/**
 * EmptyState.vue
 * ---------------------------------------------------------------------------
 * 空态展示,用于列表无结果、暂无权限等场景。
 * ---------------------------------------------------------------------------
 */
interface Props {
  title?: string;
  message: string;
}

withDefaults(defineProps<Props>(), {
  title: "暂无数据",
});
</script>

<template>
  <div
    class="flex flex-col items-center justify-center gap-2 p-6 rounded-md border border-dashed border-border bg-bg text-center"
    role="status"
  >
    <div class="text-text-primary text-sm font-medium">{{ title }}</div>
    <div class="text-text-secondary text-sm">{{ message }}</div>
  </div>
</template>