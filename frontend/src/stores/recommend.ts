/**
 * stores/recommend.ts
 * ---------------------------------------------------------------------------
 * 推荐结果 + hit_tier + promptTokens + completionTokens。
 * 命中层判定走 inferHitTier(纯函数,从 api/recommend.ts 复用)。
 * ---------------------------------------------------------------------------
 */
import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { recommendApi, parseRecommendContent, inferHitTier } from "@/api/recommend";
import type {
  HitTier,
  ParsedRecommendation,
  RecommendResult,
} from "@/types/api";

const STORAGE_KEY = "cfr.recommend.profile";
function readProfile(): string {
  try {
    return localStorage.getItem(STORAGE_KEY) ?? "";
  } catch {
    return "";
  }
}

export const useRecommendStore = defineStore("recommend", () => {
  const lastResult = ref<RecommendResult | null>(null);
  const parsed = ref<ParsedRecommendation | null>(null);
  const hitTier = ref<HitTier>("mock");
  const loading = ref(false);
  const error = ref<string | null>(null);

  const promptTokens = computed(() => lastResult.value?.promptTokens ?? null);
  const completionTokens = computed(() => lastResult.value?.completionTokens ?? null);
  const confidenceMeter = computed(() => parsed.value?.confidence ?? 0);
  const merchantIds = computed(() => parsed.value?.merchantId ?? []);
  const reason = computed(() => parsed.value?.reason ?? "");

  async function recommend(forceRegenerate = false): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      const result = await recommendApi.recommend({ forceRegenerate });
      lastResult.value = result;
      parsed.value = parseRecommendContent(result.content);
      hitTier.value = inferHitTier(parsed.value, readProfile());
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
      throw e;
    } finally {
      loading.value = false;
    }
  }

  function $reset() {
    lastResult.value = null;
    parsed.value = null;
    hitTier.value = "mock";
    loading.value = false;
    error.value = null;
  }

  return {
    lastResult,
    parsed,
    hitTier,
    loading,
    error,
    promptTokens,
    completionTokens,
    confidenceMeter,
    merchantIds,
    reason,
    recommend,
    $reset,
  };
});