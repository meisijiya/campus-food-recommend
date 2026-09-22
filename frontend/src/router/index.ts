/**
 * router/index.ts
 * ---------------------------------------------------------------------------
 * Vue Router 4 配置:hash mode + 嵌套路由 + 类型化 meta。
 * 全局导航守卫(beforeEach)由 main.ts 在 Pinia 安装后注册。
 * ---------------------------------------------------------------------------
 */
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
    meta: { requiresAuth: true, tab: "main", title: "主路径" },
    children: [
      {
        path: "",
        name: "main-zone",
        component: () => import("@/views/main/ZoneStepView.vue"),
      },
      {
        path: "cuisine",
        name: "main-cuisine",
        component: () => import("@/views/main/CuisineStepView.vue"),
      },
      {
        path: "recommend",
        name: "main-recommend",
        component: () => import("@/views/main/RecommendView.vue"),
      },
      {
        path: "merchant/:id",
        name: "main-merchant-detail",
        component: () => import("@/views/main/MerchantDetailView.vue"),
        props: true,
      },
    ],
  },
  {
    path: "/admin",
    name: "admin",
    component: () => import("@/views/AdminView.vue"),
    meta: { requiresAuth: true, requiresAdmin: true, tab: "admin", title: "管理后台" },
    children: [
      { path: "", redirect: "/admin/preheat" },
      {
        path: "preheat",
        name: "admin-preheat",
        component: () => import("@/views/admin/PreheatAdminView.vue"),
      },
      {
        path: "feature-flag",
        name: "admin-feature-flag",
        component: () => import("@/views/admin/FeatureFlagAdminView.vue"),
      },
      {
        path: "rate-limit",
        name: "admin-rate-limit",
        component: () => import("@/views/admin/RateLimitDebugView.vue"),
      },
    ],
  },
  {
    path: "/observability",
    name: "observability",
    component: () => import("@/views/ObservabilityView.vue"),
    meta: { requiresAuth: true, tab: "observability", title: "可观测性" },
  },
  {
    path: "/:pathMatch(.*)*",
    name: "not-found",
    component: () => import("@/views/NotFoundView.vue"),
    meta: { title: "404" },
  },
];

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior(_to, _from, saved) {
    return saved || { top: 0 };
  },
});

/**
 * 文档标题同步。
 */
router.afterEach((to) => {
  const title = to.meta?.title;
  if (typeof title === "string") {
    document.title = `${title} · 校园美食推荐 Demo`;
  }
});