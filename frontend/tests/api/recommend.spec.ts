/**
 * tests/api/recommend.spec.ts
 * ---------------------------------------------------------------------------
 * api/recommend.ts 纯函数覆盖:parseRecommendContent + inferHitTier
 * (ADR-0013 / api-contract.md §6.2)
 * ---------------------------------------------------------------------------
 */
import { describe, expect, it, vi } from "vitest";
import { parseRecommendContent, inferHitTier } from "@/api/recommend";

describe("parseRecommendContent", () => {
  it("valid JSON 解析返回 ParsedRecommendation", () => {
    const parsed = parseRecommendContent(
      JSON.stringify({ merchantId: ["M001", "M002"], reason: "评分高", confidence: 0.8 }),
    );
    expect(parsed.merchantId).toEqual(["M001", "M002"]);
    expect(parsed.reason).toBe("评分高");
    expect(parsed.confidence).toBe(0.8);
  });

  it("无效 JSON 抛错", () => {
    expect(() => parseRecommendContent("not json")).toThrow();
  });

  it("缺字段抛错", () => {
    expect(() =>
      parseRecommendContent(JSON.stringify({ merchantId: ["M001"], reason: "x" })),
    ).toThrow();
  });

  it("merchantId 非数组 + (array check)", () => {
    expect(() =>
      parseRecommendContent(
        JSON.stringify({ merchantId: "M001", reason: "x", confidence: 0.5 }),
      ),
    ).toThrow();
  });
});

describe("inferHitTier", () => {
  const base = { merchantId: ["M001"], reason: "推荐理由", confidence: 0.8 };

  it("dev profile → mock", () => {
    expect(inferHitTier(base, "dev")).toBe("mock");
  });

  it("test profile → mock", () => {
    expect(inferHitTier(base, "test")).toBe("mock");
  });

  it("it profile → mock", () => {
    expect(inferHitTier(base, "it")).toBe("mock");
  });

  it("bench profile + 正常 confidence → dashscope", () => {
    expect(inferHitTier(base, "bench")).toBe("dashscope");
  });

  it("bench profile + confidence<0.5 → fallback", () => {
    expect(inferHitTier({ ...base, confidence: 0.3 }, "bench")).toBe("fallback");
  });

  it("bench profile + '默认推荐' 关键词 → fallback", () => {
    expect(inferHitTier({ ...base, reason: "默认推荐" }, "bench")).toBe("fallback");
  });

  it("bench profile + 'MOCK_CATALOG' 关键词 → fallback", () => {
    expect(inferHitTier({ ...base, reason: "MOCK_CATALOG fallback" }, "bench")).toBe("fallback");
  });

  it("smoke profile + 正常 → dashscope", () => {
    expect(inferHitTier(base, "smoke")).toBe("dashscope");
  });
});