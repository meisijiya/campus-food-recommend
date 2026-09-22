# Demo 前端 design.md · 架构

> **本文件**:信息架构 / 路由树 / 状态管理(Pinia 切片) / 关键组件清单。所有前端代码组织按本基调展开。
> **配套**:`overview.md`(原则)/ `visual-system.md`(token)/ `api-contract.md`(接口契约)/ `observability-and-launch.md`(可观测性 + 上线清单)

---

## 1. 信息架构(IA)

### 1.1 顶层 3 tab(Q6 锁定)

```
┌──────────────────────────────────────────────────────────┐
│  Campus Food Recommend · Demo            [user]  [admin]   │  ← Header(56px)
├──────────────────────────────────────────────────────────┤
│  [主路径]    [管理后台]    [可观测性]                        │  ← Tab 切换(60px)
├──────────────────────────────────────────────────────────┤
│                                                          │
│   <ViewOutlet />                                         │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

**Tab 列表**(主路径 → Admin → 指标,顺序由"故事线"决定,不按接口数量平铺):

| # | tab | path | 路由懒加载 | 主要用途 |
|---|---|---|---|---|
| 1 | 主路径 | `/#/main` | `MainView` | 登录后的核心流程:zone→cuisine→merchant→推荐→点赞闭环 |
| 2 | 管理后台 | `/#/admin` | `AdminView` | 折叠 3 sub-tab:Preheat + FeatureFlag + RateLimit |
| 3 | 可观测性 | `/#/observability` | `ObservabilityView` | 自绘 Recharts 指标页 |

**登录页 `/#/login`** 是独立路由,**不挂 Tab 容器**,未登录强制跳转。

**未授权 tab 隐藏**:非 ADMIN 用户登录后不显示"管理后台" tab;非任何权限隔离之外的 tab 全部可见。

### 1.2 嵌套路由

```
/                          →  redirect → /#/main
/#/login                   →  LoginView(无 Tab 容器)
/#/main                    →  MainView(Tab 1)
   ├─ /#/main/(default)    →  ZoneStepView(选 zone,F-2 第 2 阶)
   ├─ /#/main/cuisine      →  CuisineStepView(选 cuisine,F-2 第 3 阶)
   ├─ /#/main/recommend    →  RecommendView(F-3 + hit_tier 角标)
   └─ /#/main/merchant/:id →  MerchantDetailView(F-4 + 点赞入口)
/#/admin                   →  AdminView(Tab 2,仅 ADMIN 可见)
   ├─ /#/admin/preheat     →  PreheatAdminView
   ├─ /#/admin/feature-flag →  FeatureFlagAdminView
   └─ /#/admin/rate-limit  →  RateLimitDebugView
/#/observability           →  ObservabilityView(Tab 3)
```

---

## 2. 路由形态

### 2.1 选 hash mode(`#/main`,Q6 锁定)

**理由**:
- Nginx 静态文件 `try_files $uri /index.html` 直接 serve,无需 Spring Boot `forward` fallback
- 本地开发 `python -m http.server` 或 `npx serve dist/` 都能跑,无需 SPA fallback 配置
- 招实习现场**演示稳定性**:换设备 / 换网络 / Nginx 配置失误都不会 404

**不选 history mode 的理由**:
- history mode 需要后端配 `forward:/index.html`(Spring Boot)或 Nginx `try_files`,任何一处配错就 404
- demo 现场重 Nginx 配置风险高

### 2.2 Vue Router 4 配置骨架

```ts
// router/index.ts
import { createRouter, createWebHashHistory, type RouteRecordRaw } from "vue-router";

const routes: RouteRecordRaw[] = [
  { path: "/", redirect: "/main" },
  {
    path: "/login",
    component: () => import("@/views/LoginView.vue"),
    meta: { public: true, hideTabBar: true },
  },
  {
    path: "/main",
    component: () => import("@/views/MainView.vue"),
    meta: { requiresAuth: true, tab: "main" },
    children: [
      { path: "", component: () => import("@/views/main/ZoneStepView.vue") },
      { path: "cuisine", component: () => import("@/views/main/CuisineStepView.vue") },
      { path: "recommend", component: () => import("@/views/main/RecommendView.vue") },
      { path: "merchant/:id", component: () => import("@/views/main/MerchantDetailView.vue"),
        props: true },
    ],
  },
  {
    path: "/admin",
    component: () => import("@/views/AdminView.vue"),
    meta: { requiresAuth: true, requiresAdmin: true, tab: "admin" },
    children: [
      { path: "", redirect: "/admin/preheat" },
      { path: "preheat", component: () => import("@/views/admin/PreheatAdminView.vue") },
      { path: "feature-flag", component: () => import("@/views/admin/FeatureFlagAdminView.vue") },
      { path: "rate-limit", component: () => import("@/views/admin/RateLimitDebugView.vue") },
    ],
  },
  {
    path: "/observability",
    component: () => import("@/views/ObservabilityView.vue"),
    meta: { requiresAuth: true, tab: "observability" },
  },
  { path: "/:pathMatch(.*)*", component: () => import("@/views/NotFoundView.vue") },
];

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior(to, from, saved) {
    return saved || { top: 0 };
  },
});
```

---

## 3. 状态管理(Pinia)

### 3.1 切片清单(8 个上限)

| store | 职责 | 关键 state | 关键 getter | 持久化 |
|---|---|---|---|---|
| `useAuthStore` | JWT accessToken + refreshToken + 当前用户 | `accessToken` / `refreshToken` / `user` | `isAuthenticated` / `isAdmin` / `authHeader` | `localStorage`(refreshToken 加密可选,F-16.1 决定) |
| `useSessionStore` | F-2 会话四阶段槽位 | `stage` / `zoneId` / `cuisineId` / `merchantId` | `canTransitionTo` | 不持久化(后端 Redis 30 分钟 TTL) |
| `useRecommendStore` | 推荐结果 + hit_tier + token 数 | `lastResult` / `hitTier` / `promptTokens` / `completionTokens` | `confidenceMeter` | 不持久化 |
| `useMerchantStore` | 商户详情 / 列表缓存 | `byId: Record<string, Merchant>` / `byZone: Record<string, string[]>` | `getById(id)` | 不持久化(Caffeine L0 + Redis L1 已存,前端不重复) |
| `useLikeStore` | 点赞状态(乐观更新) | `likedSet: Set<string>`(per sid) | `isLiked(merchantId)` | 不持久化 |
| `useFlagStore` | FeatureFlag 本地缓存(避免每请求打后端) | `flags: Record<string, FlagState>` | `isEnabled(flagKey, sid)` | 不持久化,`staleTime` 5 分钟 |
| `useUiStore` | 全局 UI(toast 队列 / modal / drawer) | `toasts: Toast[]` / `modal: ModalSpec | null` | — | 不持久化 |
| `useAdminStore` | Admin 子页面操作反馈 | `lastPreheat: PreheatResult | null` / `lastFlagChange: FlagConfig | null` | — | 不持久化 |

**严格 ≤ 8 个**,每个 ≤ 200 行。超 200 行必须拆。

### 3.2 切片模板示例

```ts
// stores/session.ts
import { defineStore } from "pinia";
import { sessionApi } from "@/api/session";

type Stage = "INIT" | "ZONE" | "CUISINE" | "MERCHANT";

export const useSessionStore = defineStore("session", {
  state: () => ({
    stage: "INIT" as Stage,
    zoneId: null as string | null,
    cuisineId: null as string | null,
    merchantId: null as string | null,
  }),
  getters: {
    canTransitionTo: (state) => (next: Stage) => {
      const order: Stage[] = ["INIT", "ZONE", "CUISINE", "MERCHANT"];
      const nextIdx = order.indexOf(next);
      const curIdx = order.indexOf(state.stage);
      return nextIdx === curIdx + 1; // 严格单向
    },
  },
  actions: {
    async init() { /* ... */ },
    async setZone(zoneId: string) { /* ... */ },
    async setCuisine(cuisineId: string) { /* ... */ },
    async setMerchant(merchantId: string) { /* ... */ },
    reset() { this.$reset(); },
  },
});
```

### 3.3 Axios 拦截器策略

```ts
// api/client.ts
import axios from "axios";
import { useAuthStore } from "@/stores/auth";
import { useUiStore } from "@/stores/ui";

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? "/",
  timeout: 10_000,
});

// 请求拦截:加 Authorization
api.interceptors.request.use((cfg) => {
  const auth = useAuthStore();
  if (auth.accessToken && !cfg.headers.Authorization) {
    cfg.headers.Authorization = `Bearer ${auth.accessToken}`;
  }
  return cfg;
});

// 响应拦截:统一错误处理 + 401 自动 refresh
let refreshing: Promise<string> | null = null;

api.interceptors.response.use(
  (r) => r,
  async (err) => {
    const ui = useUiStore();
    const auth = useAuthStore();
    const status = err.response?.status;

    if (status === 429) {
      const retryAfter = Number(err.response?.headers?.["retry-after"] ?? 1);
      ui.toast({
        type: "warning",
        message: `请求过快,${retryAfter} 秒后重试`,
        duration: retryAfter * 1000,
      });
      throw err;
    }

    if (status === 401 && !err.config?._retried) {
      err.config._retried = true;
      try {
        refreshing ??= auth.refresh();
        await refreshing;
        refreshing = null;
        err.config.headers.Authorization = `Bearer ${auth.accessToken}`;
        return api(err.config);
      } catch {
        refreshing = null;
        auth.logout();
        window.location.hash = "/login";
      }
    }

    // 业务错误统一 toast
    const code = err.response?.data?.code;
    if (code && code !== 0) {
      ui.toast({ type: "error", message: err.response?.data?.message ?? "请求失败" });
    }

    throw err;
  },
);
```

---

## 4. 关键组件清单

### 4.1 通用组件(`src/components/`)

| 组件 | 用途 | 关键 props | 复用位置 |
|---|---|---|---|
| `AppHeader` | 顶部导航 + 用户信息 + 退出登录 | `user: User | null` | App.vue(全 tab 共享) |
| `AppTabBar` | 3 tab 切换 | `active: "main" | "admin" | "observability"` | App.vue |
| `BaseButton` | 主/次/幽灵/危险/禁用 5 variant | `variant` / `size` / `loading` / `disabled` | 全应用 |
| `BaseInput` | 文本/邮箱/密码输入 + 错误态 | `label` / `error` / `required` / `type` | login / form 场景 |
| `BaseCard` | 卡片容器 | `title` / `actions` / `padding` | 列表 / 详情 / Admin 子页 |
| `BaseTag` | 状态标签 | `variant`(default/primary/success/warning/error)/ `size` | hit_tier / 状态 |
| `BaseModal` | 模态对话框 | `open` / `title` / `actions` | 二次确认 / 详情抽屉替代 |
| `BaseToast` / `ToastHost` | toast 队列 | — | 全局 |
| `BaseSkeleton` | 骨架屏 | `lines` / `width` | loading 态 |
| `EmptyState` | 空态 | `icon` / `title` / `action` | 列表无结果 |
| `ErrorState` | 错误态 | `message` / `retry` | 加载失败 |
| `HitTierBadge` | hit_tier 三档角标(本项目特色) | `tier: "mock" | "dashscope" | "fallback"` | RecommendView |
| `CountdownButton` | 429 倒计时按钮 | `retryAfter` / `onRetry` | RateLimitDebugView |
| `TokenCounter` | token 数显式组件 | `prompt: number` / `completion: number` | RecommendView(F-3 卖点) |

**组件约束**:
- 每个组件 ≤ 200 行,超 200 拆分子组件
- `script setup lang="ts"`,TypeScript strict
- Props 用 `defineProps<T>()` 泛型,**不用 runtime prop validation**
- 事件用 `defineEmits<E>()`,不带 native Event 类型

### 4.2 视图组件(`src/views/`)

按 1.2 嵌套路由列出,**不**在此文件贴模板代码,**组件契约**如下:

| 视图 | 关键 UI | 数据源 | 错误处理 |
|---|---|---|---|
| `LoginView` | 双输入框 + 主按钮 + 演示账号提示卡 | `useAuthStore` | 表单内联错误 + toast |
| `ZoneStepView` | 12 个商圈卡片网格(2~3 列) | `useMerchantStore.byZone` + `/api/merchant?zoneId=` | ErrorState + 重试 |
| `CuisineStepView` | 当前 zone 下菜系网格 | 同上 | ErrorState |
| `RecommendView` | 推荐理由横幅 + 3-5 张商户卡(每张带 HitTierBadge / TokenCounter / 点赞按钮) | `useRecommendStore` + `/api/recommend` | ErrorState + 重新推荐按钮 |
| `MerchantDetailView` | 详情卡 / 营业时段(若 `merchant-detail-new` flag 开)/ 点赞大按钮 / 同 zone 推荐 3 个 | `useMerchantStore.getById` + `/api/merchant/{id}` | 404 → 引导返回主路径 |
| `PreheatAdminView` | 触发按钮 + 结果卡(merchantCount / zoneCount / hotCount / elapsedMs)+ "再触发一次"按钮 | `useAdminStore` + `/admin/preheat/trigger` | toast 错误 |
| `FeatureFlagAdminView` | flagKey 下拉 + 当前 config 表单 + 修改保存按钮 | `useFlagStore` + `/admin/feature-flag/**` | toast 错误 |
| `RateLimitDebugView` | "触发限流" 按钮 + 计数图(每用户每分钟触发数)+ CountdownButton | 自写计时器 + 真实接口触发 | 429 触发后用 CountdownButton |
| `ObservabilityView` | 4 业务指标卡片(Recharts line/bar)+ 6 技术指标卡片(同)+ 自动刷新开关(默认 5 秒) | `/actuator/prometheus` 自绘 parse | ErrorState(指标源失败) |

---

## 5. 目录树(F-16.1 实施时)

```
frontend/
├── index.html
├── package.json
├── tsconfig.json
├── tailwind.config.ts
├── vite.config.ts
├── public/
└── src/
    ├── main.ts
    ├── App.vue
    ├── api/
    │   ├── client.ts              # axios 实例 + 拦截器
    │   ├── auth.ts
    │   ├── session.ts
    │   ├── recommend.ts
    │   ├── like.ts
    │   ├── merchant.ts
    │   ├── preheat.ts
    │   ├── feature-flag.ts
    │   └── actuator.ts            # /actuator/prometheus parse
    ├── stores/
    │   ├── auth.ts
    │   ├── session.ts
    │   ├── recommend.ts
    │   ├── merchant.ts
    │   ├── like.ts
    │   ├── flag.ts
    │   ├── ui.ts
    │   └── admin.ts
    ├── router/
    │   └── index.ts
    ├── components/                # 通用组件(§4.1)
    │   ├── AppHeader.vue
    │   ├── AppTabBar.vue
    │   ├── BaseButton.vue
    │   ├── BaseInput.vue
    │   ├── BaseCard.vue
    │   ├── BaseTag.vue
    │   ├── BaseModal.vue
    │   ├── ToastHost.vue
    │   ├── BaseSkeleton.vue
    │   ├── EmptyState.vue
    │   ├── ErrorState.vue
    │   ├── HitTierBadge.vue
    │   ├── CountdownButton.vue
    │   └── TokenCounter.vue
    ├── views/
    │   ├── LoginView.vue
    │   ├── MainView.vue           # 主路径容器(嵌套路由出口)
    │   ├── main/
    │   │   ├── ZoneStepView.vue
    │   │   ├── CuisineStepView.vue
    │   │   ├── RecommendView.vue
    │   │   └── MerchantDetailView.vue
    │   ├── AdminView.vue          # Admin 容器(嵌套路由出口)
    │   ├── admin/
    │   │   ├── PreheatAdminView.vue
    │   │   ├── FeatureFlagAdminView.vue
    │   │   └── RateLimitDebugView.vue
    │   ├── ObservabilityView.vue
    │   └── NotFoundView.vue
    ├── lib/
    │   ├── prometheus-parser.ts   # parsePrometheus(text) → Metric[]
    │   └── env.ts                 # VITE_API_BASE 类型化
    ├── types/
    │   ├── api.d.ts               # 全 9 类接口 TS 类型,与 api-reference.md 字段一一对应
    │   └── domain.d.ts            # User / Merchant / Session / Flag / HitTier
    └── styles/
        ├── tokens.css             # 从 visual-system.md §8 直接拷
        └── base.css               # @tailwind base/components/utilities + reset
```

---

## 6. 关键交互模式

### 6.1 加载状态

| 场景 | 视觉 |
|---|---|
| 列表加载 | `BaseSkeleton` 5 行 |
| 卡片加载 | `BaseSkeleton` 3 行 |
| 详情加载 | `BaseSkeleton` 标题 + 段落 |
| 表单提交 | 按钮 `loading` 态(spinner inside button) |
| 推荐请求(慢) | `BaseSkeleton` + 骨架屏带 "AI 思考中..." 文字 |

**统一规则**:**不用**全局 spinner(全屏 loading overlay);用**局部骨架**,保留上下文。

### 6.2 乐观更新

点赞是典型乐观更新场景:

```ts
// stores/like.ts(节选)
actions: {
  async toggle(merchantId: string) {
    const was = this.likedSet.has(merchantId);
    // 1. 立即翻转 UI
    if (was) this.likedSet.delete(merchantId);
    else this.likedSet.add(merchantId);
    // 2. 发请求;失败回滚
    try {
      await likeApi.like(merchantId);
    } catch (err) {
      if (was) this.likedSet.add(merchantId);
      else this.likedSet.delete(merchantId);
      throw err;
    }
  },
},
```

UI 上:点击瞬间按钮变 active(无 loading);200ms 内请求成功保留 active;失败回滚 + toast。

### 6.3 自动刷新(可观测性页)

```ts
// views/ObservabilityView.vue
const autoRefresh = ref(true);
const { data, error, refresh } = await useFetch('/actuator/prometheus', { ... });

watch(autoRefresh, (v) => {
  if (v) {
    const id = setInterval(refresh, 5000);
    onUnmounted(() => clearInterval(id));
  }
});
```

UI 顶部"自动刷新"开关,默认开;关掉后只显示"手动刷新"按钮。

---

## 7. TypeScript 契约

`types/api.d.ts` 与 `docs/api/api-reference.md` 字段**一一对应**,不创造字段、不偷懒
**纪律**:字段名 / 类型 / null 标注与文档严格一致(例:`Merchant.heatScore: number | null`,不写 `number`)。

```ts
// types/api.d.ts(节选)
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  // 注意:没有 tokenType 字段
}

export interface SessionContext {
  stage: "INIT" | "ZONE" | "CUISINE" | "MERCHANT";
  zoneId: string | null;
  cuisineId: string | null;
  merchantId: string | null;
}

export interface Merchant {
  id: string;
  name: string;
  zoneId: string;
  cuisineId: string;
  tags: string | null;
  heatScore: number | null;
  // 注意:没有 hitTier / openHours(除非 flag merchant-detail-new 开)
}

export interface RecommendResult {
  content: string;       // AI 输出的原始 JSON 字符串
  stage: "INIT" | "ZONE" | "CUISINE" | "MERCHANT";
  promptTokens: number | null;
  completionTokens: number | null;
  // 注意:没有 recommendations[] / modelVersion
}

export interface ParsedRecommendation {
  merchantId: string[];
  reason: string;
  confidence: number;
}

export interface LikeResponse {
  liked: boolean;
  // 60s 重复:code=0 message="already liked" liked=false
}

export interface PreheatResult {
  timestamp: string;
  merchantCount: number;
  zoneCount: number;
  hotCount: number;
  elapsedMs: number;
}

export interface FlagConfig {
  mode: "ALL_ON" | "ALL_OFF" | "WHITELIST_ONLY" | "PERCENTAGE";
  percentage: number | null;
  whitelist: number[] | null;
  enabled: boolean;
}

export interface FlagCheckResult {
  flagKey: string;
  enabled: boolean;
  mode: string;
  studentId: number | null;
}

export interface ApiError {
  code: number;       // 0/40000/40100/40300/40400/42900/50000
  message: string;
  data: null;
}
```

---

## 8. 性能预算

| 指标 | 目标 | 测量 |
|---|---|---|
| 首屏(登录页) | < 1.5s LCP | Lighthouse |
| 路由切换 | < 300ms(懒加载 chunk) | Performance API |
| Bundle size(gzipped)| < 200KB(不含 Recharts)| `vite build --report` |
| 内存占用(单 tab) | < 100MB | DevTools Memory |
| 60fps 滚动 | 路由切换 / 列表滚动 | DevTools Performance |

### 8.1 Bundle 控制

- `recharts` 按需引入:`import { LineChart, Line, ... } from 'recharts'`(不用 `import * as`)
- `lucide-vue-next` 按需引入:`import { ChevronRight } from 'lucide-vue-next'`
- 路由懒加载已配
- 不引入 moment / dayjs(本 demo 几乎不需日期处理,`Intl.DateTimeFormat` 已够)

---

## 9. 可测试性

| 测试类型 | 工具 | 覆盖目标 |
|---|---|---|
| 单元 | Vitest | Pinia 切片 + 工具函数(`prometheus-parser` 等) |
| 组件 | Vitest + @vue/test-utils + happy-dom | BaseButton / HitTierBadge / CountdownButton 等关键组件 |
| 端到端 | Playwright | 招实习现场完整旅程(登录 → 主路径 → 点赞 → Admin → 指标) |
| a11y | axe-core/playwright | 各视图 0 violations |

**纪律**:
- 不写"组件 100% 覆盖率"硬指标——只测**关键路径 + 边界**
- 不引入 MSW(mock service worker)做"假后端"测试——本 demo 跑真实后端即可

---

## 10. 与现有工程约定

| 项 | 约定 |
|---|---|
| 包管理器 | `npm`(与后端 Maven 命名对齐);可换 pnpm,F-16.1 决定 |
| Node 版本 | ≥ 20 LTS(本机测试时锁定) |
| TypeScript | strict mode 必开 |
| ESLint | `@vue/eslint-config-typescript` + `eslint-plugin-vue` |
| Prettier | 默认配置 + `singleQuote: false`(与后端 Java 字符串风格对齐) |
| Git hooks | simple-git-hooks:pre-commit 跑 `lint-staged`(Vue 文件 Prettier + TS 文件 ESLint) |

**纪律**:与后端 Maven 项目风格**对齐**(同 quote 规则、同 .editorconfig),不引入 ESLint 新奇插件。