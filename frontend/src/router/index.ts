import { createRouter, createWebHashHistory, type RouteRecordRaw } from "vue-router";

const routes: RouteRecordRaw[] = [
  { path: "/", redirect: "/main" },
  {
    path: "/login",
    name: "login",
    component: () => import("@/views/LoginView.vue"),
    meta: { public: true, hideTabBar: true, title: "登录" },
  },
  {
    path: "/main",
    name: "main",
    component: () => import("@/views/MainView.vue"),
    meta: { title: "主路径" },
  },
  {
    path: "/admin",
    name: "admin",
    component: () => import("@/views/AdminView.vue"),
    meta: { title: "管理后台" },
  },
  {
    path: "/observability",
    name: "observability",
    component: () => import("@/views/ObservabilityView.vue"),
    meta: { title: "可观测性" },
  },
  {
    path: "/:pathMatch(.*)*",
    name: "not-found",
    component: () => import("@/views/NotFoundView.vue"),
    meta: { hideTabBar: false, title: "404" },
  },
];

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior(_to, _from, saved) {
    return saved || { top: 0 };
  },
});