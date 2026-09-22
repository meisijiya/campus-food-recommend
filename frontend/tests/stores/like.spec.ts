/**
 * tests/stores/like.spec.ts
 * ---------------------------------------------------------------------------
 * useLikeStore 关键 reducer 覆盖(乐观更新)
 * ---------------------------------------------------------------------------
 */
import { describe, expect, it, vi, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useLikeStore } from "@/stores/like";
import * as likeApiModule from "@/api/like";

beforeEach(() => {
  setActivePinia(createPinia());
});

describe("useLikeStore", () => {
  it("初始状态空", () => {
    const s = useLikeStore();
    expect(s.isLiked("M001")).toBe(false);
    expect(s.likedCount).toBe(0);
  });

  it("toggle() 立即翻转 UI", async () => {
    vi.spyOn(likeApiModule.likeApi, "like").mockResolvedValue({ liked: true });
    const s = useLikeStore();
    const p = s.toggle("M001");
    // 同步:立即已 liked
    expect(s.isLiked("M001")).toBe(true);
    await p;
    expect(s.isLiked("M001")).toBe(true);
  });

  it("toggle() 失败回滚", async () => {
    vi.spyOn(likeApiModule.likeApi, "like").mockRejectedValue(new Error("500"));
    const s = useLikeStore();
    await expect(s.toggle("M001")).rejects.toThrow("500");
    expect(s.isLiked("M001")).toBe(false);
  });

  it("toggle() 已 liked 状态下点 → 取消 like;服务端确认后保持", async () => {
    vi.spyOn(likeApiModule.likeApi, "like").mockResolvedValue({ liked: false });
    const s = useLikeStore();
    s.likedSet.add("M001");
    await s.toggle("M001");
    expect(s.isLiked("M001")).toBe(false);
  });

  it("isPending() 跟踪 in-flight toggle", () => {
    const s = useLikeStore();
    expect(s.isPending("M001")).toBe(false);
    s.pendingIds.add("M001");
    expect(s.isPending("M001")).toBe(true);
  });

  it("$reset() 清 likedSet + pendingIds", () => {
    const s = useLikeStore();
    s.likedSet.add("M001");
    s.pendingIds.add("M001");
    s.$reset();
    expect(s.isLiked("M001")).toBe(false);
    expect(s.isPending("M001")).toBe(false);
  });
});