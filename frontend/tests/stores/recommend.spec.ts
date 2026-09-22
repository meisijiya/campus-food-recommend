/**
 * tests/stores/recommend.spec.ts
 * ---------------------------------------------------------------------------
 * useRecommendStore 关键 reducer 覆盖
 * ---------------------------------------------------------------------------
 */
import { describe, expect, it, vi, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useRecommendStore } from "@/stores/recommend";
import * as recommendApiModule from "@/api/recommend";

beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
});

const recommendResult = {
  content: JSON.stringify({ merchantId: ["M001"], reason: "评分高", confidence: 0.85 }),
  stage: "MERCHANT" as const,
  promptTokens: 100,
  completionTokens: 50,
};

describe("useRecommendStore", () => {
  it("初始 state 为空 + tier=mock", () => {
    const s = useRecommendStore();
    expect(s.lastResult).toBeNull();
    expect(s.parsed).toBeNull();
    expect(s.hitTier).toBe("mock");
    expect(s.merchantIds).toEqual([]);
    expect(s.confidenceMeter).toBe(0);
  });

  it("recommend() 解析 content + 填 hit_tier", async () => {
    vi.spyOn(recommendApiModule.recommendApi, "recommend").mockResolvedValue(recommendResult);
    const s = useRecommendStore();
    await s.recommend();
    expect(s.merchantIds).toEqual(["M001"]);
    expect(s.reason).toBe("评分高");
    expect(s.promptTokens).toBe(100);
    expect(s.completionTokens).toBe(50);
    expect(s.hitTier).toBe("mock"); // dev profile 默认
  });

  it("recommend() 失败抛错并写 error", async () => {
    vi.spyOn(recommendApiModule.recommendApi, "recommend").mockRejectedValue(new Error("500"));
    const s = useRecommendStore();
    await expect(s.recommend()).rejects.toThrow("500");
    expect(s.error).toBe("500");
  });

  it("$reset() 重置所有", async () => {
    vi.spyOn(recommendApiModule.recommendApi, "recommend").mockResolvedValue(recommendResult);
    const s = useRecommendStore();
    await s.recommend();
    s.$reset();
    expect(s.lastResult).toBeNull();
    expect(s.merchantIds).toEqual([]);
  });
});