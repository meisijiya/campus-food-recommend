/**
 * api/recommend.ts
 * ---------------------------------------------------------------------------
 * /api/recommend 调用 + 内容解析。
 * content 是 stringified JSON,前端必须 JSON.parse(content) 拿结构化字段。
 * ---------------------------------------------------------------------------
 */
import { api, type ApiAxiosRequestConfig } from "./client";
import type {
  ApiResponse,
  ParsedRecommendation,
  RecommendRequest,
  RecommendResult,
} from "@/types/api";

export const recommendApi = {
  async recommend(req: RecommendRequest = {}): Promise<RecommendResult> {
    const { data } = await api.post<ApiResponse<RecommendResult>>("/api/recommend", req);
    return data.data;
  },

  /** 不走 401 自动 refresh(因为是 raw 调用,不在拦截器里误回 401)。 */
  async raw(): Promise<RecommendResult> {
    const cfg: ApiAxiosRequestConfig = { silent: true };
    const { data } = await api.post<ApiResponse<RecommendResult>>(
      "/api/recommend",
      {},
      cfg,
    );
    return data.data;
  },
};

/**
 * 解析 RecommendResult.content。失败抛错。
 */
export function parseRecommendContent(content: string): ParsedRecommendation {
  try {
    const parsed = JSON.parse(content) as ParsedRecommendation;
    if (
      !Array.isArray(parsed.merchantId) ||
      typeof parsed.reason !== "string" ||
      typeof parsed.confidence !== "number"
    ) {
      throw new Error("invalid recommend content");
    }
    return parsed;
  } catch (err) {
    throw new Error(
      `parse recommend content failed: ${err instanceof Error ? err.message : String(err)}`,
    );
  }
}

/**
 * 推断 hit_tier(启发式,api-contract.md §6.2)。
 * - bench/smoke profile + content 命中 fallback 关键词 → "fallback"
 * - bench/smoke profile → "dashscope"
 * - 其他(dev/test/it,默认) → "mock"
 */
export function inferHitTier(
  parsed: ParsedRecommendation,
  profile: string,
): "mock" | "dashscope" | "fallback" {
  const isBenchLike = profile === "bench" || profile === "smoke";
  const isFallbackByContent =
    parsed.confidence < 0.5 ||
    /默认推荐|基础规则|MOCK_CATALOG/i.test(parsed.reason);

  if (isBenchLike && isFallbackByContent) return "fallback";
  if (isBenchLike) return "dashscope";
  return "mock";
}