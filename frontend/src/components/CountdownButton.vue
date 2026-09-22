<script setup lang="ts">
/**
 * CountdownButton.vue
 * ---------------------------------------------------------------------------
 * 429 限流倒计时按钮(api-contract.md §5.1)。
 * 接收 retryAfter header,内部 setTimeout tick 倒计时。
 * 倒计时归零后按钮恢复"重试"。
 * ---------------------------------------------------------------------------
 */
import { ref, watch, onUnmounted } from "vue";
import BaseButton from "./BaseButton.vue";

interface Props {
  retryAfter: number;
  label?: string;
  variant?: "primary" | "secondary" | "ghost";
}

const props = withDefaults(defineProps<Props>(), {
  label: "重试",
  variant: "secondary",
});

const emit = defineEmits<{
  (e: "retry"): void;
}>();

const remaining = ref(props.retryAfter);
const timerId = ref<number | null>(null);

function clearTimer() {
  if (timerId.value !== null) {
    clearTimeout(timerId.value);
    timerId.value = null;
  }
}

function tick() {
  if (remaining.value > 0) {
    remaining.value -= 1;
    timerId.value = window.setTimeout(tick, 1000);
  }
}

watch(
  () => props.retryAfter,
  (v) => {
    clearTimer();
    remaining.value = v;
    if (v > 0) {
      timerId.value = window.setTimeout(tick, 1000);
    }
  },
  { immediate: true },
);

onUnmounted(clearTimer);

function handleClick() {
  emit("retry");
}
</script>

<template>
  <BaseButton
    :variant="variant"
    :disabled="remaining > 0"
    @click="handleClick"
  >
    {{ remaining > 0 ? `${remaining} 秒后${label}` : label }}
  </BaseButton>
</template>