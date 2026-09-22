/**
 * stores/auth.ts
 * ---------------------------------------------------------------------------
 * JWT accessToken + refreshToken + 当前用户。
 * - localStorage 持久化(access / refresh / user 三个 key)
 * - refresh() 返回新 accessToken(axios 拦截器在 401 时调用)
 * - logout() 清 store + localStorage + 跳 /#/login
 * Pinia 模式:https://pinia.vuejs.org/core-concepts/#setup-stores
 * ---------------------------------------------------------------------------
 */
import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { authApi } from "@/api/auth";
import type { User } from "@/types/api";

const LS_ACCESS = "cfr.auth.accessToken";
const LS_REFRESH = "cfr.auth.refreshToken";
const LS_USER = "cfr.auth.user";

function loadString(key: string): string {
  try {
    return localStorage.getItem(key) ?? "";
  } catch {
    return "";
  }
}

function loadUser(): User | null {
  try {
    const raw = localStorage.getItem(LS_USER);
    return raw ? (JSON.parse(raw) as User) : null;
  } catch {
    return null;
  }
}

function saveString(key: string, value: string) {
  try {
    if (value) localStorage.setItem(key, value);
    else localStorage.removeItem(key);
  } catch {
    /* ignore quota / privacy mode */
  }
}

function saveUser(user: User | null) {
  try {
    if (user) localStorage.setItem(LS_USER, JSON.stringify(user));
    else localStorage.removeItem(LS_USER);
  } catch {
    /* ignore */
  }
}

export const useAuthStore = defineStore("auth", () => {
  // state
  const accessToken = ref<string>(loadString(LS_ACCESS));
  const refreshToken = ref<string>(loadString(LS_REFRESH));
  const user = ref<User | null>(loadUser());

  // getters
  const isAuthenticated = computed(() => !!accessToken.value);
  const isAdmin = computed(() => user.value?.role === "ADMIN");

  // actions
  async function login(username: string, password: string): Promise<void> {
    const auth = await authApi.login({ username, password });
    accessToken.value = auth.accessToken;
    refreshToken.value = auth.refreshToken;
    saveString(LS_ACCESS, auth.accessToken);
    saveString(LS_REFRESH, auth.refreshToken);
    // 后端 /auth/login 暂不返回 user;这里从 username 推断 role(招实习 demo 简化)
    // 真实生产应该再调 /me 端点或后端 login body 加 user
    const inferred: User = { username, role: username.startsWith("admin") ? "ADMIN" : "USER" };
    user.value = inferred;
    saveUser(inferred);
  }

  async function refresh(): Promise<string> {
    const auth = await authApi.refresh(refreshToken.value);
    accessToken.value = auth.accessToken;
    refreshToken.value = auth.refreshToken;
    saveString(LS_ACCESS, auth.accessToken);
    saveString(LS_REFRESH, auth.refreshToken);
    return auth.accessToken;
  }

  function logout(): void {
    accessToken.value = "";
    refreshToken.value = "";
    user.value = null;
    saveString(LS_ACCESS, "");
    saveString(LS_REFRESH, "");
    saveUser(null);
  }

  function $reset() {
    logout();
  }

  return {
    accessToken,
    refreshToken,
    user,
    isAuthenticated,
    isAdmin,
    login,
    refresh,
    logout,
    $reset,
  };
});