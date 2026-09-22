/**
 * stores/admin.ts
 * ---------------------------------------------------------------------------
 * Admin 操作反馈:Preheat 触发结果 + FeatureFlag 修改结果。
 * ---------------------------------------------------------------------------
 */
import { ref } from "vue";
import { defineStore } from "pinia";
import { preheatApi } from "@/api/preheat";
import { featureFlagApi } from "@/api/feature-flag";
import type { FlagConfig, FlagConfigUpdate, PreheatResult } from "@/types/api";

export const useAdminStore = defineStore("admin", () => {
  const lastPreheat = ref<PreheatResult | null>(null);
  const lastFlagChange = ref<FlagConfig | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function triggerPreheat(): Promise<PreheatResult> {
    loading.value = true;
    error.value = null;
    try {
      const r = await preheatApi.trigger();
      lastPreheat.value = r;
      return r;
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      throw e;
    } finally {
      loading.value = false;
    }
  }

  async function getFlag(flagKey: string): Promise<FlagConfig> {
    const r = await featureFlagApi.getConfig(flagKey);
    return r;
  }

  async function setFlag(flagKey: string, body: FlagConfigUpdate): Promise<FlagConfig> {
    loading.value = true;
    error.value = null;
    try {
      const r = await featureFlagApi.setConfig(flagKey, body);
      lastFlagChange.value = r;
      return r;
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      throw e;
    } finally {
      loading.value = false;
    }
  }

  function $reset() {
    lastPreheat.value = null;
    lastFlagChange.value = null;
    loading.value = false;
    error.value = null;
  }

  return {
    lastPreheat,
    lastFlagChange,
    loading,
    error,
    triggerPreheat,
    getFlag,
    setFlag,
    $reset,
  };
});