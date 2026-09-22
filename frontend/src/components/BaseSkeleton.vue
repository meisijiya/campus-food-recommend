<script setup lang="ts">
/**
 * BaseSkeleton.vue
 * ---------------------------------------------------------------------------
 * 骨架屏,代替通用 loading spinner。
 * ---------------------------------------------------------------------------
 */
interface Props {
  lines?: number;
  width?: string;
}

withDefaults(defineProps<Props>(), {
  lines: 3,
  width: "100%",
});
</script>

<template>
  <div class="flex flex-col gap-2" role="status" aria-label="加载中">
    <div
      v-for="i in lines"
      :key="i"
      :style="{ width: typeof width === 'number' ? `${width}px` : width }"
      class="h-3 rounded-sm bg-primary-50 animate-pulse"
    />
  </div>
</template>