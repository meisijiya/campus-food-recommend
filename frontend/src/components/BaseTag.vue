<script setup lang="ts">
/**
 * BaseTag.vue
 * ---------------------------------------------------------------------------
 * 状态标签。5 variant:default / primary / success / warning / error。
 * ---------------------------------------------------------------------------
 */
import { computed } from "vue";

type Variant = "default" | "primary" | "success" | "warning" | "error";
type Size = "sm" | "md";

interface Props {
  variant?: Variant;
  size?: Size;
}

const props = withDefaults(defineProps<Props>(), {
  variant: "default",
  size: "sm",
});

const variantClass = computed(() => {
  switch (props.variant) {
    case "default":
      return "bg-primary-50 text-primary-700 border-primary-100";
    case "primary":
      return "bg-primary-500 text-white border-primary-500";
    case "success":
      return "bg-success-500 text-white border-success-500";
    case "warning":
      return "bg-warning-500 text-white border-warning-500";
    case "error":
      return "bg-error-500 text-white border-error-500";
  }
});

const sizeClass = computed(() =>
  props.size === "sm" ? "text-xs px-1.5 py-0.5" : "text-sm px-2 py-1",
);
</script>

<template>
  <span
    :class="[
      'inline-flex items-center rounded-sm border font-medium',
      variantClass,
      sizeClass,
    ]"
  >
    <slot />
  </span>
</template>