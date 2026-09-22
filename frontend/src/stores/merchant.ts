/**
 * stores/merchant.ts
 * ---------------------------------------------------------------------------
 * 商户详情 / 列表缓存。前端 L0 缓存(内存),与后端 Caffeine L0 / Redis L1 互补。
 * getById() 命中即返回,未命中走 API。
 * listByZone() 缓存 zone → merchantId[]。
 * ---------------------------------------------------------------------------
 */
import { ref } from "vue";
import { defineStore } from "pinia";
import { merchantApi } from "@/api/merchant";
import type { Merchant } from "@/types/api";

export const useMerchantStore = defineStore("merchant", () => {
  const byId = ref<Record<string, Merchant>>({});
  const byZone = ref<Record<string, string[]>>({});
  const loading = ref(false);
  const error = ref<string | null>(null);

  function getById(id: string): Merchant | undefined {
    return byId.value[id];
  }

  async function fetchById(id: string): Promise<Merchant> {
    const cached = byId.value[id];
    if (cached) return cached;
    loading.value = true;
    error.value = null;
    try {
      const m = await merchantApi.getById(id);
      byId.value[m.id] = m;
      return m;
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      throw e;
    } finally {
      loading.value = false;
    }
  }

  async function fetchListByZone(zoneId: string): Promise<Merchant[]> {
    loading.value = true;
    error.value = null;
    try {
      const list = await merchantApi.listByZone(zoneId);
      const ids: string[] = [];
      for (const m of list) {
        byId.value[m.id] = m;
        ids.push(m.id);
      }
      byZone.value[zoneId] = ids;
      return list;
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      throw e;
    } finally {
      loading.value = false;
    }
  }

  function $reset() {
    byId.value = {};
    byZone.value = {};
    loading.value = false;
    error.value = null;
  }

  return {
    byId,
    byZone,
    loading,
    error,
    getById,
    fetchById,
    fetchListByZone,
    $reset,
  };
});