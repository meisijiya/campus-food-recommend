# Demo 前端 design.md · 接口契约

> **本文件**:9 类接口契约 + 鉴权流程 + 错误处理 + 429 UX + hit_tier 显式标注。
> **真相源**:`docs/api/api-reference.md`(F-17 已定稿,2026-09-22)。本文件**不复制字段定义**,只描述前端**如何消费**。
> **配套**:`architecture.md`(状态管理 / 组件) / `visual-system.md`(错误态视觉)

---

## 1. 接口契约映射(9 类 → 视图)

| 接口 | 路径 | Method | 鉴权 | 视图组件 | Pinia 切片 |
|---|---|---|---|---|---|
| Auth · login | `/api/auth/login` | POST | 否 | `LoginView` | `useAuthStore` |
| Auth · refresh | `/api/auth/refresh` | POST | 否(refreshToken) | axios 拦截器 | `useAuthStore` |
| Session · init | `/api/session/init` | POST | Bearer | `MainView.mounted`(自动调 1 次) | `useSessionStore` |
| Session · zone | `/api/session/zone` | POST | Bearer | `ZoneStepView` | `useSessionStore` |
| Session · cuisine | `/api/session/cuisine` | POST | Bearer | `CuisineStepView` | `useSessionStore` |
| Session · merchant | `/api/session/merchant` | POST | Bearer | `RecommendView`(推荐后调) | `useSessionStore` |
| Recommend | `/api/recommend` | POST | Bearer | `RecommendView` | `useRecommendStore` |
| Like | `/api/like/{merchantId}` | POST | Bearer | `MerchantDetailView` / `RecommendView` | `useLikeStore` |
| Merchant · getById | `/api/merchant/{id}` | GET | Bearer | `MerchantDetailView` | `useMerchantStore` |
| Merchant · listByZone | `/api/merchant?zoneId=` | GET | Bearer | `ZoneStepView` / `CuisineStepView` | `useMerchantStore` |
| Admin · preheat trigger | `/admin/preheat/trigger` | POST | Bearer(ADMIN)| `PreheatAdminView` | `useAdminStore` |
| Admin · feature-flag list | `/admin/feature-flag` | GET | Bearer(ADMIN)| `FeatureFlagAdminView` | — |
| Admin · feature-flag get | `/admin/feature-flag/{flagKey}` | GET | Bearer(ADMIN)| `FeatureFlagAdminView` | `useFlagStore` |
| Admin · feature-flag set | `/admin/feature-flag/{flagKey}` | POST | Bearer(ADMIN)| `FeatureFlagAdminView` | `useFlagStore` |
| Public · feature-flag check | `/api/feature-flag/{flagKey}/check` | GET | 否 | `MainView.mounted`(预热)| `useFlagStore` |
| Rate limit demo | 任何 `/api/**`(非豁免)| — | Bearer | `RateLimitDebugView` | — |
| Actuator · prometheus | `/actuator/prometheus` | GET | 否 | `observability` 内部 | — |

---

## 2. baseURL 与代理配置

### 2.1 Vite dev(开发模式)

```ts
// vite.config.ts
export default defineConfig({
  server: {
    proxy: {
      "/api":     { target: "http://127.0.0.1:8080", changeOrigin: true },
      "/admin":   { target: "http://127.0.0.1:8080", changeOrigin: true },
      "/actuator":{ target: "http://127.0.0.1:8080", changeOrigin: true },
    },
  },
});
```

### 2.2 生产 build(走 nginx 80)

```ts
// src/lib/env.ts
export const env = {
  apiBase: import.meta.env.VITE_API_BASE ?? "/",
};
```

nginx.conf(已有,F-16.5 不重写):

```nginx
location / {
    try_files $uri /index.html;  # SPA fallback(hash mode 不依赖,但显式 ok)
}
location /api/ { proxy_pass http://cfr-app:8080; }
location /admin/ { proxy_pass http://cfr-app:8080; }
location /actuator/ { proxy_pass http://cfr-app:8080; }
```

### 2.3 baseURL 选择矩阵

| 场景 | baseURL | 备注 |
|---|---|---|
| 本地开发(`npm run dev`)| `/`(经 Vite 代理)| 默认 |
| Docker compose(已配 nginx)| `/`(经 Nginx 80)| 默认 |
| 直连 Spring Boot(罕见)| `http://127.0.0.1:8080` | `VITE_API_BASE=http://127.0.0.1:8080 npm run dev` |

> **不提供 UI 切换按钮**(Q8 答复 d 不需要);demo 现场只走 nginx 80。

---

## 3. 鉴权流程

### 3.1 JWT 拦截器(architecture.md §3.3 已配)

**字段要点**(引自 `docs/api/api-reference.md` §1.1):
- `AuthResponse` **没有 `tokenType` 字段**;前端固定 `Authorization: Bearer <accessToken>`
- `expiresIn: 7200`(秒,2 小时)— accessToken TTL
- refreshToken 复用,过期才重新登录

### 3.2 401 自动 refresh 流程

```
请求 A 触发 401
  ↓
axios 拦截器捕获 status=401
  ↓
判断 err.config._retried? 若是 → 直接跳登录(避免无限循环)
  ↓
未 retried → 调 POST /api/auth/refresh { refreshToken: <当前 refreshToken> }
  ├─ 200 → 拿新 accessToken,更新 store,重放请求 A(在原 err.config 上 _retried=true)
  └─ 401 → refreshToken 也过期 → 清 store + localStorage + 跳 /#/login
```

**关键边界**:
- 同一时刻多个 401:用模块级 `refreshing: Promise<string> | null` 串行化(只发 1 次 refresh)
- refresh 期间到达的 401:等待 `refreshing` 完成后取新 token 重放
- refresh 接口本身 401:不走"自动重试 refresh",直接跳登录

### 3.3 localStorage 存储

| key | 内容 | 加密 | 过期处理 |
|---|---|---|---|
| `cfr.auth.accessToken` | JWT string | 否(本 demo 无 XSS 防护投入) | refresh 失败清 |
| `cfr.auth.refreshToken` | JWT string | 否 | 401 清 |
| `cfr.auth.user` | `{ username, role }` | 否 | logout 清 |

**纪律**:F-16.1+ 实施时如要"加密",必须先开 ADR,不直接 `crypto-js` 引入。

---

## 4. 错误处理

### 4.1 错误码速查(`docs/api/api-reference.md` §0.2)

| code | HTTP | 含义 | 前端 UX |
|---|---|---|---|
| `0` | 200/201 | ok | 正常返回 |
| `40000` | 400 | 参数非法 | 表单内联错误(<BaseInput error>) |
| `40100` | 401 | 未鉴权 | axios 拦截器自动 refresh(§3.2) |
| `40300` | 403 | 无权限 | 跳 NotFoundView(避免暴露 403 字样) |
| `40400` | 404 | 资源不存在 | ErrorState + "返回主路径"按钮 |
| `42900` | 429 | 限流拒绝 | CountdownButton(§6) |
| `50000` | 500 | 内部异常 | ErrorState + "重试"按钮 + Sentry(可选) |

### 4.2 axios 拦截器统一处理

```ts
// api/client.ts(节选)
api.interceptors.response.use(
  (r) => r,
  async (err) => {
    const ui = useUiStore();
    const status = err.response?.status;
    const code = err.response?.data?.code;

    // 1. 限流 → CountdownButton 由视图层接 throw,拦截器不处理 429(让调用方决定)
    // 2. 业务错误 → toast(只在"用户主动触发的请求"上,后台 polling 不 toast)
    if (code && code !== 0 && !err.config?.silent) {
      ui.toast({
        type: code === 42900 ? "warning" : "error",
        message: err.response?.data.message ?? "请求失败",
      });
    }

    throw err;  // 让 catch 处理
  },
);
```

### 4.3 视图层 catch 模式

```ts
// 推荐结果页:catch 内部降级显示 fallback UI
const data = ref<RecommendResult | null>(null);
const error = ref<ApiError | null>(null);

try {
  data.value = await recommendApi.recommend();
} catch (err) {
  error.value = err.response?.data;
  // 不弹 toast — 已在拦截器弹过;这里只更新 error state
}
```

### 4.4 表单内联错误 vs toast 错误

| 场景 | 表现 |
|---|---|
| 表单提交失败(`40000`) | `<BaseInput error="用户名不能为空">`,**不** toast |
| 全局操作失败(点赞 / 推荐) | toast |
| 详情页 404 | ErrorState + 返回按钮,**不** toast(避免长视图被遮) |
| 限流 429 | CountdownButton + toast(短提示)|
| 401 自动 refresh 失败 | 全局 toast + 跳登录页 |

---

## 5. 限流 429 UX(Q8 锁定)

### 5.1 CountdownButton

```vue
<!-- components/CountdownButton.vue -->
<script setup lang="ts">
const props = defineProps<{ retryAfter: number; onRetry: () => Promise<void> | void; }>();
const remaining = ref(props.retryAfter);
const running = ref(false);

const tick = () => {
  if (remaining.value > 0) {
    remaining.value -= 1;
    setTimeout(tick, 1000);
  }
};

watch(() => props.retryAfter, (v) => {
  remaining.value = v;
  if (v > 0) tick();
});

const handleClick = async () => {
  running.value = true;
  try {
    await props.onRetry();
    remaining.value = 0;
  } finally {
    running.value = false;
  }
};
</script>

<template>
  <BaseButton
    variant="secondary"
    :disabled="remaining > 0"
    :loading="running"
    @click="handleClick"
  >
    {{ remaining > 0 ? `${remaining} 秒后重试` : "重试" }}
  </BaseButton>
</template>
```

### 5.2 RateLimitDebugView 招实习现场演示

**预置脚本**(招实习现场触发):

```ts
// views/admin/RateLimitDebugView.vue
const triggerBurst = async () => {
  // 在 1 秒内发 30 次 /api/recommend,撞穿用户桶(100/10s)或 API 桶(1000/500s)
  const burst = 30;
  const promises = Array.from({ length: burst }, () =>
    recommendApi.raw().catch((err) => err.response?.status)
  );
  const statuses = await Promise.all(promises);
  successCount.value = statuses.filter((s) => s === 200).length;
  rejected429.value = statuses.filter((s) => s === 429).length;
  // 触发后用 CountdownButton,1 秒后恢复
};
```

**演示步骤**(招实习现场):
1. 打开 `/admin/rate-limit` tab
2. 点"触发限流"
3. UI 立刻显示:`成功 X 个 / 限流拒绝 Y 个`
4. 按钮变 "1 秒后重试",1 秒后恢复"重试"
5. 讲解:"这就是 F-7 双层令牌桶行为,前端能 UX 兜住"

---

## 6. hit_tier 显式标注(F-3 现场卖点)

### 6.1 后端字段

`/api/recommend` 响应**没有 `hit_tier` 字段**(api-reference.md §3.1)。前端必须**解析 + 推断**。

### 6.2 推断策略

后端 Spring AI advisor chain(基于 CONTEXT.md §10 + api-reference.md §3.1):

```
MockChatModel (dev/test/it profile)
  ↓ 不合规
DashScopeChatModel (bench/smoke profile)
  ↓ 不合规
ReflectiveRetryAdvisor (≤2 次)
  ↓ 仍不合规
RuleBasedFallbackAdvisor (MOCK_CATALOG 兜底)
  ↓ 最终
ApiResponse.ok(...)
```

**前端推断规则**(按响应字段):

| Tier | 触发条件(可观测) | 前端策略 |
|---|---|---|
| `mock` | dev/test/it profile(`SPRING_PROFILES_ACTIVE` 未设或为 `dev` / `test` / `it`) | 显式标 `mock` |
| `dashscope` | bench/smoke profile(`SPRING_PROFILES_ACTIVE=bench` 启动) | 显式标 `dashscope` |
| `fallback` | dev / bench profile 都能触发 fallback,但**前端无法直接感知** | 显式:响应里 `confidence < 0.5` **或** `reason` 文本含 `"默认推荐"` / `"基础规则"` 关键词 → 标 `fallback` |

### 6.3 实现

```ts
// stores/recommend.ts
export type HitTier = "mock" | "dashscope" | "fallback";

export const inferHitTier = (result: RecommendResult, env: { profile: string }): HitTier => {
  const parsed = JSON.parse(result.content) as ParsedRecommendation;
  const isFallbackByProfile =
    env.profile === "bench" || env.profile === "smoke";
  const isFallbackByContent =
    parsed.confidence < 0.5 ||
    /默认推荐|基础规则|MOCK_CATALOG/i.test(parsed.reason);

  if (isFallbackByProfile && isFallbackByContent) return "fallback";
  if (isFallbackByProfile) return "dashscope";
  return "mock";
};
```

> **注意**:本推断是**启发式**,不与后端日志 100% 对齐。后端要在日志里打 `hit_tier`(F-17 落地的 `recommend_latency_seconds{endpoint, hit_tier}` tag 已含)。前端**演示**用启发式,**复盘**靠后端日志。

### 6.4 HitTierBadge 组件

```vue
<!-- components/HitTierBadge.vue -->
<script setup lang="ts">
import type { HitTier } from "@/stores/recommend";
const props = defineProps<{ tier: HitTier }>();

const tierConfig: Record<HitTier, { label: string; bg: string; text: string }> = {
  mock:      { label: "mock",      bg: "var(--hit-tier-mock-bg)",      text: "var(--hit-tier-mock-text)" },
  dashscope: { label: "dashscope", bg: "var(--hit-tier-dashscope-bg)", text: "var(--hit-tier-dashscope-text)" },
  fallback:  { label: "fallback",  bg: "var(--hit-tier-fallback-bg)",  text: "var(--hit-tier-fallback-text)" },
};

const cfg = computed(() => tierConfig[props.tier]);
</script>

<template>
  <span
    class="inline-flex items-center px-2 py-1 rounded-sm text-xs font-medium"
    :style="{ background: cfg.bg, color: cfg.text }"
    :aria-label="`推荐命中层 ${cfg.label}`"
  >
    {{ cfg.label }}
  </span>
</template>
```

### 6.5 TokenCounter 组件(配合 hit_tier)

```vue
<!-- components/TokenCounter.vue -->
<script setup lang="ts">
defineProps<{ prompt: number | null; completion: number | null; }>();
</script>

<template>
  <span class="text-xs text-text-secondary font-num">
    {{ prompt ?? 0 }} + {{ completion ?? 0 }} tokens
  </span>
</template>
```

招实习现场讲解 F-3 bullet 时,这俩组件 + RecommendView 卡片角标一并展示。

---

## 7. CORS 配置(后端)

后端 SecurityConfig 默认允许的 origin(招实习现场):

```yaml
# application.yml(已有,F-16.1 不重配)
spring:
  web:
    cors:
      allowed-origins: "http://localhost,http://127.0.0.1,http://localhost:5173,http://127.0.0.1:5173"
```

> Vite dev 默认 5173 端口;本机不变;如换 port,改 yml 同步。

---

## 8. FeatureFlag 本地缓存

```ts
// stores/flag.ts
export const useFlagStore = defineStore("flag", {
  state: () => ({
    flags: {} as Record<string, FlagCheckResult>,
    fetchedAt: {} as Record<string, number>,
    staleMs: 5 * 60 * 1000,  // 5 分钟 stale
  }),
  actions: {
    async check(flagKey: string, sid: number) {
      const now = Date.now();
      if (now - (this.fetchedAt[flagKey] ?? 0) < this.staleMs && this.flags[flagKey]) {
        return this.flags[flagKey];
      }
      const r = await featureFlagApi.check(flagKey, sid);
      this.flags[flagKey] = r;
      this.fetchedAt[flagKey] = now;
      return r;
    },
    invalidate(flagKey?: string) {
      if (flagKey) { delete this.flags[flagKey]; delete this.fetchedAt[flagKey]; }
      else { this.flags = {}; this.fetchedAt = {}; }
    },
  },
});
```

Admin 修改 flag 后,**Admin 页面手动调 `invalidate()`**,主路径下次 check 自动刷新。

---

## 9. 字段细节陷阱(从前端消费角度)

> 来源:对比 `docs/api/api-reference.md` 与代码实际,前端最容易踩的几个点。

| 坑 | 说明 | 前端处理 |
|---|---|---|
| `AuthResponse` 无 `tokenType` 字段 | 文档 §1.1 已澄清 | 固定 `Bearer ` 前缀,不读字段 |
| `SlotRequest.value` 不是 `zoneId` / `cuisineId` / `merchantId` | 文档 §2.1 警告 | 统一 body:`{ value: "<id>" }` |
| `SessionContext.zoneId` 等带 `Id` 后缀 | 文档 §2.1 警告 | TS 类型严格用 `zoneId` / `cuisineId` / `merchantId`,不要 `merchant_id` |
| `RecommendResult.content` 是 stringified JSON | 文档 §3.1 警告 | 必须 `JSON.parse(content)` 拿 `merchantId[]` / `reason` / `confidence` |
| `RecommendResult` 无 `recommendations[]` / `modelVersion` | 文档 §3.1 警告 | 不引用这两字段 |
| `LikeResponse` 60s 重复:`liked=false message="already liked"` HTTP 200 code=0 | 文档 §4.1 警告 | 不当错误,UI 保持"已点赞"态(乐观更新避免回滚) |
| `Merchant.heatScore` 是 `number \| null` | 文档 §5.1 警告 | 渲染时 `{{ merchant.heatScore?.toFixed(1) ?? "—" }}` |
| `Merchant` 标准输出不含 `hitTier` / `openHours` | 文档 §5.1 警告 | 不引用;`merchant-detail-new` flag 开时 detail endpoint 才加 `openHours`(可选 UI 显隐) |
| `/api/merchant?zoneId=` zoneId 不存在返 200 `data: []`,不 404 | 文档 §5.2 警告 | 不弹错,直接显示 EmptyState |
| `/api/auth/login` 401 走 `40100`,**不**走 OAuth2 标准的 `401` body | 文档 §1.1 | catch 处理 |
| `40100` 与 40100(无空格)| 注意 body 内 code 是数字 `40100`,不是字符串 | TS `code: number` |
| 限流 `Retry-After` 固定 1 秒,不走配置 | 文档 §8.5 | CountdownButton 直接读 header,不需要拉配置 |
| `/actuator/prometheus` 仅 prod profile 启用 | 文档 §7.3 | dev 提示"prod profile 才可看" |

---

## 10. 接口契约变更追踪

`docs/api/api-reference.md` 是唯一真相源。F-16.1+ 实施若发现字段不一致:

1. **第一步**:在工单 evidence 段写"前端实测字段 X 与 api-reference.md 不一致"
2. **第二步**:不动前端代码,先 `git log` + `grep` 后端代码确认真相
3. **第三步**:若是 api-reference.md 错,改文档;若是后端代码错,开 ADR + 工单修后端
4. **纪律**:**前端不背契约差异的锅**,但也不擅自容忍(避免 demo 现场翻车)