import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import { router } from "./router";
import { installInterceptors } from "./api/client";
import { useAuthStore } from "./stores/auth";
import { useUiStore } from "./stores/ui";
import "./styles/base.css";

const app = createApp(App);
const pinia = createPinia();
app.use(pinia);
app.use(router);

// 在 Pinia 安装后再装 axios 拦截器(避免 store ↔ client 循环 import)
installInterceptors(
  () => {
    const auth = useAuthStore();
    return {
      get accessToken() {
        return auth.accessToken;
      },
      get refreshToken() {
        return auth.refreshToken;
      },
      refresh: () => auth.refresh(),
      logout: () => auth.logout(),
    };
  },
  () => {
    const ui = useUiStore();
    return { toast: (t) => ui.toast(t) };
  },
);

// 全局导航守卫:鉴权 + ADMIN 校验(Vue Router 4.x return-based)
router.beforeEach((to) => {
  const auth = useAuthStore();
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: "login", query: { redirect: to.fullPath } };
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    // 403 隐藏:跳 404,不暴露 403 字样
    return { name: "not-found" };
  }
  // 已登录访问 /login → 跳到主路径
  if (to.name === "login" && auth.isAuthenticated) {
    return { name: "main" };
  }
  return true;
});

app.mount("#app");