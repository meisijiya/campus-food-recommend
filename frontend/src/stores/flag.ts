/**
 * stores/flag.ts
 * ---------------------------------------------------------------------------
 * FeatureFlag 本地缓存,避免每请求打后端。5 分钟 stale。
 * - check(flagKey, sid) 自动按 staleTime 决定是否重查
 * - invalidate(flagKey) 由 Admin 修改 flag 后手动调用
 * ---------------------------------------------------------------------------
 */
import { ref } from "vue";
import { defineStore } from "pinia";
import { featureFlagApi } from "@/api/feature-flag";
import type { FlagCheckResult } from "@/types/api";

const STALE_MS = 5 * 60 * 1000;

export const useFlagStore = defineStore("flag", () => {
  const flags = ref<Record<string, FlagCheckResult>>({});
  const fetchedAt = ref<Record<string, number>>({});

  async function check(flagKey: string, studentId: number): Promise<FlagCheckResult> {
    const now = Date.now();
    const ts = fetchedAt.value[flagKey] ?? 0;
    if (flags.value[flagKey] && now - ts < STALE_MS) {
      return flags.value[flagKey];
    }
    const r = await featureFlagApi.check(flagKey, studentId);
    flags.value[flagKey] = r;
    fetchedAt.value[flagKey] = now;
    return r;
  }

  async function isEnabled(flagKey: string, studentId: number): Promise<boolean> {
    const r = await check(flagKey, studentId);
    return r.enabled;
  }

  function invalidate(flagKey?: string) {
    if (flagKey) {
      delete flags.value[flagKey];
      delete fetchedAt.value[flagKey];
    } else {
      flags.value = {};
      fetchedAt.value = {};
    }
  }

  function $reset() {
    flags.value = {};
    fetchedAt.value = {};
  }

  return { flags, fetchedAt, check, isEnabled, invalidate, $reset };
});