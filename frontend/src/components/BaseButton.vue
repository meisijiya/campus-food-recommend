<script setup lang="ts">
/**
 * BaseButton.vue
 * ---------------------------------------------------------------------------
 * 5 variant:primary / secondary / ghost / danger / disabled。
 * loading 态由父组件控制 disabled + 自带 spinner。
 * ---------------------------------------------------------------------------
 */
import { computed } from "vue";

type Variant = "primary" | "secondary" | "ghost" | "danger";
type Size = "sm" | "md" | "lg";

interface Props {
  variant?: Variant;
  size?: Size;
  type?: "button" | "submit" | "reset";
  loading?: boolean;
  disabled?: boolean;
  block?: boolean;
}

const props = withDefaults(defineProps<Props>(), {
  variant: "primary",
  size: "md",
  type: "button",
  loading: false,
  disabled: false,
  block: false,
});

defineEmits<{
  (e: "click", ev: MouseEvent): void;
}>();

const variantClass = computed(() => {
  switch (props.variant) {
    case "primary":
      return "bg-primary-500 text-white hover:bg-primary-600 active:bg-primary-700";
    case "secondary":
      return "bg-surface text-text-primary border border-border hover:bg-primary-50";
    case "ghost":
      return "bg-transparent text-text-primary hover:bg-primary-50";
    case "danger":
      return "bg-error-500 text-white hover:opacity-90";
  }
});

const sizeClass = computed(() => {
  switch (props.size) {
    case "sm": return "text-xs px-2 py-1";
    case "md": return "text-sm px-3 py-1.5";
    case "lg": return "text-base px-4 py-2";
  }
});

const computedDisabled = computed(() => props.disabled || props.loading);
</script>

<template>
  <button
    :type="type"
    :disabled="computedDisabled"
    :class="[
      'inline-flex items-center justify-center gap-2 rounded-sm font-medium transition-colors',
      'focus:outline-none focus:ring-2 focus:ring-primary-500/30',
      'disabled:opacity-50 disabled:cursor-not-allowed',
      variantClass,
      sizeClass,
      block && 'w-full',
    ]"
    @click="(ev) => $emit('click', ev)"
  >
    <span v-if="loading" class="inline-block w-3 h-3 border-2 border-current border-t-transparent rounded-full animate-spin" />
    <slot />
  </button>
</template>