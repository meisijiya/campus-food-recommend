/**
 * types/api.d.ts
 * ---------------------------------------------------------------------------
 * 与 docs/api/api-reference.md(F-17 定稿)字段一一对应。
 * 纪律:字段名 / 类型 / null 标注与文档严格一致;不创造字段、不偷懒。
 * ---------------------------------------------------------------------------
 */

/** 通用 ApiResponse 包装。code=0 表示业务成功,其他 code 走错误码体系。 */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

/** 业务错误对象(从 err.response.data 抽取)。code 是数字,不是字符串。 */
export interface ApiErrorBody {
  code: number;
  message: string;
  data: null;
}

/* ===========================================================================
 * 1. Auth(api-reference.md §1)
 * =========================================================================== */

/** /api/auth/login 请求 body。 */
export interface LoginRequest {
  username: string;
  password: string;
}

/** /api/auth/login + /api/auth/refresh 响应。注意:无 tokenType 字段。 */
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

/** /api/auth/refresh 请求 body。 */
export interface RefreshRequest {
  refreshToken: string;
}

/* ===========================================================================
 * 2. Session(api-reference.md §2)
 * =========================================================================== */

/** F-2 严格单向流转:INIT → ZONE → CUISINE → MERCHANT。 */
export type SessionStage = "INIT" | "ZONE" | "CUISINE" | "MERCHANT";

/** SlotRequest 是统一对象,value 字段根据接口语义变。 */
export interface SlotRequest {
  value: string;
}

/** SessionContext 响应,带 Id 后缀的字段严格匹配。 */
export interface SessionContext {
  stage: SessionStage;
  zoneId: string | null;
  cuisineId: string | null;
  merchantId: string | null;
}

/* ===========================================================================
 * 3. Recommend(api-reference.md §3)
 * =========================================================================== */

/** /api/recommend 请求 body。 */
export interface RecommendRequest {
  forceRegenerate?: boolean;
}

/**
 * /api/recommend 响应。
 * - content 是 stringified JSON,前端必须 `JSON.parse(content)` 拿 `merchantId[]` / `reason` / `confidence`。
 * - 没有 `recommendations[]` / `modelVersion` 字段。
 */
export interface RecommendResult {
  content: string;
  stage: SessionStage;
  promptTokens: number | null;
  completionTokens: number | null;
}

/** 解析 content 后的对象。 */
export interface ParsedRecommendation {
  merchantId: string[];
  reason: string;
  confidence: number;
}

/** 推断 hit_tier,前端演示用启发式。 */
export type HitTier = "mock" | "dashscope" | "fallback";

/* ===========================================================================
 * 4. Like(api-reference.md §4)
 * =========================================================================== */

/**
 * /api/like/{merchantId} 响应。
 * 60s 重复点踩:code=0 message="already liked" liked=false,不当错误,UI 保持。
 */
export interface LikeResponse {
  liked: boolean;
}

/* ===========================================================================
 * 5. Merchant(api-reference.md §5)
 * =========================================================================== */

/**
 * Merchant 标准输出。
 * - heatScore 是 number | null。
 * - 标准输出不含 hitTier / openHours;后者由 merchant-detail-new flag 控制的可选 detail 端点提供。
 */
export interface Merchant {
  id: string;
  name: string;
  zoneId: string;
  cuisineId: string;
  tags: string | null;
  heatScore: number | null;
}

/** Merchant 详情可选项(merchant-detail-new flag 开时 detail endpoint 返回)。 */
export interface MerchantDetailExtra {
  openHours?: string;
}

/* ===========================================================================
 * 6. Admin Preheat(api-reference.md §6)
 * =========================================================================== */

/** /admin/preheat/trigger 响应。 */
export interface PreheatResult {
  timestamp: string;
  merchantCount: number;
  zoneCount: number;
  hotCount: number;
  elapsedMs: number;
}

/* ===========================================================================
 * 7. FeatureFlag(api-reference.md §9)
 * =========================================================================== */

/** FeatureFlag 配置模式。 */
export type FlagMode = "ALL_ON" | "ALL_OFF" | "WHITELIST_ONLY" | "PERCENTAGE";

/** Admin /admin/feature-flag/{key} 响应。 */
export interface FlagConfig {
  mode: FlagMode;
  percentage: number | null;
  whitelist: number[] | null;
  enabled: boolean;
}

/** Admin /admin/feature-flag/{key} POST 请求 body。 */
export interface FlagConfigUpdate {
  mode: FlagMode;
  percentage?: number | null;
  whitelist?: number[] | null;
  enabled: boolean;
}

/** Public /api/feature-flag/{key}/check 响应。 */
export interface FlagCheckResult {
  flagKey: string;
  enabled: boolean;
  mode: string;
  studentId: number | null;
}

/* ===========================================================================
 * 8. 错误码常量(api-reference.md §0.2)
 * =========================================================================== */

/** 业务错误码集合。 */
export const ApiErrorCode = {
  OK: 0,
  BAD_REQUEST: 40000,
  UNAUTHORIZED: 40100,
  FORBIDDEN: 40300,
  NOT_FOUND: 40400,
  RATE_LIMITED: 42900,
  INTERNAL: 50000,
} as const;

export type ApiErrorCodeValue = (typeof ApiErrorCode)[keyof typeof ApiErrorCode];

/* ===========================================================================
 * 9. Domain types
 * =========================================================================== */

/** 当前用户,仅 username + role(后端 /me 端点返回的最小集)。 */
export interface User {
  username: string;
  role: "USER" | "ADMIN";
}

/** Toast 队列元素。 */
export type ToastType = "success" | "warning" | "error" | "info";

export interface Toast {
  id: string;
  type: ToastType;
  message: string;
  duration: number;
}