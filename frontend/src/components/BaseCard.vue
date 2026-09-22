<script setup lang="ts">
/**
 * BaseCard.vue
 * ---------------------------------------------------------------------------
 * 卡片容器。可选 title / actions 顶部栏,默认 padding=md。
 * ---------------------------------------------------------------------------
 */
interface Props {
  title?: string;
  padding?: "none" | "sm" | "md" | "lg";
  bordered?: boolean;
}

withDefaults(defineProps<Props>(), {
  padding: "md",
  bordered: true,
});
</script>

<template>
  <section
    :class="[
      'rounded-md bg-surface',
      bordered && 'border border-border',
      padding === 'none' && 'p-0',
      padding === 'sm' && 'p-3',
      padding === 'md' && 'p-4',
      padding === 'lg' && 'p-6',
    ]"
  >
    <header v-if="title || $slots.actions" class="flex items-center justify-between mb-3">
      <h3 v-if="title" class="text-base font-semibold text-text-primary">{{ title }}</h3>
      <slot v-else name="title" />
      <slot name="actions" />
    </header>
    <slot />
  </section>
</template>