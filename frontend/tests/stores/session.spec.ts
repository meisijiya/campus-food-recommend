/**
 * tests/stores/session.spec.ts
 * ---------------------------------------------------------------------------
 * useSessionStore 关键 reducer 覆盖:
 * - canTransitionTo 严格单向(INIT → ZONE → CUISINE → MERCHANT)
 * - init/setZone/setCuisine/setMerchant 调用对应 API
 * - $reset 重置
 * ---------------------------------------------------------------------------
 */
import { describe, expect, it, vi, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useSessionStore } from "@/stores/session";
import * as sessionApiModule from "@/api/session";
import type { SessionContext } from "@/types/api";

beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
});

const makeCtx = (stage: SessionContext["stage"], id: string | null): SessionContext => ({
  stage,
  zoneId: stage !== "INIT" ? id : null,
  cuisineId: stage === "CUISINE" || stage === "MERCHANT" ? "C001" : null,
  merchantId: stage === "MERCHANT" ? "M001" : null,
});

describe("useSessionStore", () => {
  it("初始 stage = INIT, canTransitionTo 只允许 ZONE", () => {
    const s = useSessionStore();
    expect(s.stage).toBe("INIT");
    expect(s.canTransitionTo("ZONE")).toBe(true);
    expect(s.canTransitionTo("CUISINE")).toBe(false);
    expect(s.canTransitionTo("MERCHANT")).toBe(false);
  });

  it("ZONE 阶段只允许 → CUISINE", async () => {
    vi.spyOn(sessionApiModule.sessionApi, "init").mockResolvedValue(makeCtx("ZONE", "Z001"));
    const s = useSessionStore();
    await s.init();
    expect(s.stage).toBe("ZONE");
    expect(s.canTransitionTo("CUISINE")).toBe(true);
    expect(s.canTransitionTo("MERCHANT")).toBe(false);
    expect(s.canTransitionTo("ZONE")).toBe(false);
  });

  it("CUISINE 阶段只允许 → MERCHANT", async () => {
    vi.spyOn(sessionApiModule.sessionApi, "init").mockResolvedValue(makeCtx("CUISINE", "Z001"));
    const s = useSessionStore();
    await s.init();
    expect(s.canTransitionTo("MERCHANT")).toBe(true);
    expect(s.canTransitionTo("ZONE")).toBe(false);
  });

  it("MERCHANT 阶段不允许再前移", async () => {
    vi.spyOn(sessionApiModule.sessionApi, "init").mockResolvedValue(makeCtx("MERCHANT", "Z001"));
    const s = useSessionStore();
    await s.init();
    expect(s.canTransitionTo("ZONE")).toBe(false);
    expect(s.canTransitionTo("CUISINE")).toBe(false);
    expect(s.canTransitionTo("MERCHANT")).toBe(false);
  });

  it("setZone() 替换 ctx 并 stage = ZONE", async () => {
    vi.spyOn(sessionApiModule.sessionApi, "setZone").mockResolvedValue(makeCtx("ZONE", "Z001"));
    const s = useSessionStore();
    await s.setZone("Z001");
    expect(s.zoneId).toBe("Z001");
    expect(s.stage).toBe("ZONE");
  });

  it("setCuisine() 替换 ctx 并 stage = CUISINE(需先经过 ZONE)", async () => {
    vi.spyOn(sessionApiModule.sessionApi, "setZone").mockResolvedValue(makeCtx("ZONE", "Z001"));
    vi.spyOn(sessionApiModule.sessionApi, "setCuisine").mockResolvedValue(makeCtx("CUISINE", "Z001"));
    const s = useSessionStore();
    await s.setZone("Z001");
    await s.setCuisine("C001");
    expect(s.cuisineId).toBe("C001");
    expect(s.stage).toBe("CUISINE");
  });

  it("setMerchant() 替换 ctx 并 stage = MERCHANT(需先经过 CUISINE)", async () => {
    vi.spyOn(sessionApiModule.sessionApi, "init").mockResolvedValue(makeCtx("CUISINE", "Z001"));
    vi.spyOn(sessionApiModule.sessionApi, "setMerchant").mockResolvedValue(makeCtx("MERCHANT", "Z001"));
    const s = useSessionStore();
    await s.init(); // → CUISINE
    await s.setMerchant("M001");
    expect(s.merchantId).toBe("M001");
    expect(s.stage).toBe("MERCHANT");
  });

  it("非法流转抛错,不调 API", async () => {
    // 先初始化到 MERCHANT 阶段,然后尝试 setCuisine(回退)→ 非法
    vi.spyOn(sessionApiModule.sessionApi, "init").mockResolvedValue(makeCtx("MERCHANT", "Z001"));
    const setCuisineSpy = vi.spyOn(sessionApiModule.sessionApi, "setCuisine");
    const s = useSessionStore();
    await s.init();
    await expect(s.setCuisine("C001")).rejects.toThrow("invalid transition: CUISINE");
    expect(setCuisineSpy).not.toHaveBeenCalled();
  });

  it("$reset() 重置回 INIT", async () => {
    vi.spyOn(sessionApiModule.sessionApi, "init").mockResolvedValue(makeCtx("MERCHANT", "Z001"));
    const s = useSessionStore();
    await s.init();
    s.$reset();
    expect(s.stage).toBe("INIT");
    expect(s.zoneId).toBeNull();
  });
});