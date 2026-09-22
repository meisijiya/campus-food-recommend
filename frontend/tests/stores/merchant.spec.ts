/**
 * tests/stores/merchant.spec.ts
 * ---------------------------------------------------------------------------
 * useMerchantStore 关键 reducer 覆盖:getById 缓存 / fetchListByZone
 * ---------------------------------------------------------------------------
 */
import { describe, expect, it, vi, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useMerchantStore } from "@/stores/merchant";
import * as merchantApiModule from "@/api/merchant";
import type { Merchant } from "@/types/api";

beforeEach(() => {
  setActivePinia(createPinia());
});

const sampleMerchants: Merchant[] = [
  { id: "M001", name: "兰州拉面", zoneId: "Z001", cuisineId: "C001", tags: "面食", heatScore: 4.5 },
  { id: "M002", name: "麻辣烫",   zoneId: "Z001", cuisineId: "C003", tags: null,    heatScore: null },
];

describe("useMerchantStore", () => {
  it("初始 byId 空 + getById 返 undefined", () => {
    const s = useMerchantStore();
    expect(s.getById("M001")).toBeUndefined();
  });

  it("fetchById() 缓存到 byId", async () => {
    vi.spyOn(merchantApiModule.merchantApi, "getById").mockResolvedValue(sampleMerchants[0]);
    const s = useMerchantStore();
    const m = await s.fetchById("M001");
    expect(m.name).toBe("兰州拉面");
    expect(s.getById("M001")).toEqual(sampleMerchants[0]);
  });

  it("fetchById() 第二次调用命中缓存(不调 API)", async () => {
    const spy = vi.spyOn(merchantApiModule.merchantApi, "getById").mockResolvedValue(sampleMerchants[0]);
    const s = useMerchantStore();
    await s.fetchById("M001");
    await s.fetchById("M001");
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("fetchById() 失败抛错", async () => {
    vi.spyOn(merchantApiModule.merchantApi, "getById").mockRejectedValue(new Error("404"));
    const s = useMerchantStore();
    await expect(s.fetchById("missing")).rejects.toThrow("404");
    expect(s.error).toBe("404");
  });

  it("fetchListByZone() 同时填充 byId + byZone", async () => {
    vi.spyOn(merchantApiModule.merchantApi, "listByZone").mockResolvedValue(sampleMerchants);
    const s = useMerchantStore();
    const list = await s.fetchListByZone("Z001");
    expect(list).toHaveLength(2);
    expect(s.byZone["Z001"]).toEqual(["M001", "M002"]);
    expect(s.getById("M002")).toEqual(sampleMerchants[1]);
  });

  it("fetchListByZone() 返回空数组不弹错", async () => {
    vi.spyOn(merchantApiModule.merchantApi, "listByZone").mockResolvedValue([]);
    const s = useMerchantStore();
    const list = await s.fetchListByZone("Z999");
    expect(list).toEqual([]);
    expect(s.byZone["Z999"]).toEqual([]);
  });

  it("$reset() 清所有缓存", async () => {
    vi.spyOn(merchantApiModule.merchantApi, "getById").mockResolvedValue(sampleMerchants[0]);
    const s = useMerchantStore();
    await s.fetchById("M001");
    s.$reset();
    expect(s.getById("M001")).toBeUndefined();
  });
});