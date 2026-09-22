<script setup lang="ts">
/**
 * LoginView · F-16.2 完整登录页
 * ---------------------------------------------------------------------------
 * - BaseInput + 内联错误(form 表单错误走 40000,不弹 toast)
 * - BaseButton + loading(防重复提交)
 * - useAuthStore.login() → 成功跳 redirect 或 /main
 * - 演示账号提示卡(招实习现场)
 * ---------------------------------------------------------------------------
 */
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { useUiStore } from "@/stores/ui";
import BaseInput from "@/components/BaseInput.vue";
import BaseButton from "@/components/BaseButton.vue";
import BaseCard from "@/components/BaseCard.vue";

const auth = useAuthStore();
const ui = useUiStore();
const route = useRoute();
const router = useRouter();

const username = ref("");
const password = ref("");
const usernameError = ref<string>("");
const passwordError = ref<string>("");
const submitting = ref(false);

interface DemoAccount {
  label: string;
  username: string;
  password: string;
  role: string;
}

const demoAccounts: DemoAccount[] = [
  { label: "普通用户", username: "user",   password: "user123",   role: "USER" },
  { label: "管理员",   username: "admin",  password: "admin123",  role: "ADMIN" },
];

function fillDemo(d: DemoAccount) {
  username.value = d.username;
  password.value = d.password;
}

async function onSubmit() {
  usernameError.value = "";
  passwordError.value = "";
  if (!username.value) {
    usernameError.value = "用户名不能为空";
    return;
  }
  if (!password.value) {
    passwordError.value = "密码不能为空";
    return;
  }
  submitting.value = true;
  try {
    await auth.login(username.value, password.value);
    ui.toast({ type: "success", message: "登录成功" });
    const redirect = typeof route.query.redirect === "string" ? route.query.redirect : "/main";
    router.push(redirect);
  } catch (err) {
    // 表单错误走 input.error;网络/业务错误已在拦截器 toast
    const message = err instanceof Error ? err.message : "登录失败";
    if (message.includes("40000") || message.includes("参数")) {
      passwordError.value = message;
    }
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <div class="max-w-md mx-auto p-6 space-y-4">
    <BaseCard title="登录 · Campus Food Recommend">
      <form class="space-y-3" data-testid="login-form" @submit.prevent="onSubmit">
        <BaseInput
          v-model="username"
          label="用户名"
          placeholder="请输入用户名"
          required
          autocomplete="username"
          :error="usernameError"
        />
        <BaseInput
          v-model="password"
          label="密码"
          type="password"
          placeholder="请输入密码"
          required
          autocomplete="current-password"
          :error="passwordError"
        />
        <BaseButton type="submit" :loading="submitting" block data-testid="login-submit">
          登录
        </BaseButton>
      </form>
    </BaseCard>

    <BaseCard title="演示账号(招实习现场)" bordered>
      <div class="space-y-2">
        <div
          v-for="d in demoAccounts"
          :key="d.username"
          class="flex items-center justify-between gap-2 text-sm"
        >
          <div>
            <span class="font-medium">{{ d.label }}</span>
            <span class="text-text-secondary ml-2">
              {{ d.username }} / {{ d.password }}
            </span>
            <BaseTag size="sm" :variant="d.role === 'ADMIN' ? 'primary' : 'default'">
              {{ d.role }}
            </BaseTag>
          </div>
          <button
            type="button"
            class="text-xs text-primary-500 hover:underline"
            @click="fillDemo(d)"
          >
            填充
          </button>
        </div>
      </div>
    </BaseCard>
  </div>
</template>