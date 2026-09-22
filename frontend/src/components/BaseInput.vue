<script setup lang="ts">
/**
 * BaseInput.vue
 * ---------------------------------------------------------------------------
 * 文本/邮箱/密码输入 + error 内联提示(用于表单错误,不弹 toast)
 * ---------------------------------------------------------------------------
 */
import { computed } from "vue";

interface Props {
  modelValue: string;
  label?: string;
  type?: "text" | "email" | "password" | "number";
  placeholder?: string;
  error?: string;
  required?: boolean;
  disabled?: boolean;
  autocomplete?: string;
  inputId?: string;
}

const props = withDefaults(defineProps<Props>(), {
  type: "text",
  required: false,
  disabled: false,
});

const emit = defineEmits<{
  (e: "update:modelValue", value: string): void;
  (e: "blur"): void;
}>();

const inputId = computed(() => props.inputId ?? `base-input-${Math.random().toString(36).slice(2, 9)}`);
const hasError = computed(() => !!props.error);
</script>

<template>
  <div class="flex flex-col gap-1">
    <label v-if="label" :for="inputId" class="text-sm font-medium text-text-primary">
      {{ label }}<span v-if="required" class="text-error-500 ml-1">*</span>
    </label>
    <input
      :id="inputId"
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :required="required"
      :disabled="disabled"
      :autocomplete="autocomplete"
      :aria-invalid="hasError"
      :aria-describedby="hasError ? `${inputId}-error` : undefined"
      class="w-full px-3 py-2 border rounded-sm outline-none transition-colors
             bg-surface text-text-primary placeholder:text-text-disabled
             focus:ring-2 focus:ring-primary-500/30"
      :class="hasError ? 'border-error-500' : 'border-border'"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      @blur="emit('blur')"
    />
    <span
      v-if="hasError"
      :id="`${inputId}-error`"
      role="alert"
      class="text-xs text-error-500"
    >
      {{ error }}
    </span>
  </div>
</template>