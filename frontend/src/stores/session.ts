/**
 * stores/session.ts
 * ---------------------------------------------------------------------------
 * F-2 严格单向流转:INIT → ZONE → CUISINE → MERCHANT。
 * 每个槽位变更走对应 sessionApi.* 端点,store 不本地 mutate stage,
 * 而是拿后端返回的 SessionContext 整体替换,保证与后端一致。
 * canTransitionTo getter 控制 UI 跳步合理性。
 * ---------------------------------------------------------------------------
 */
import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { sessionApi } from "@/api/session";
import type { SessionContext, SessionStage } from "@/types/api";

const STAGE_ORDER: SessionStage[] = ["INIT", "ZONE", "CUISINE", "MERCHANT"];

export const useSessionStore = defineStore("session", () => {
  const ctx = ref<SessionContext>({
    stage: "INIT",
    zoneId: null,
    cuisineId: null,
    merchantId: null,
  });
  const loading = ref(false);

  const stage = computed(() => ctx.value.stage);
  const zoneId = computed(() => ctx.value.zoneId);
  const cuisineId = computed(() => ctx.value.cuisineId);
  const merchantId = computed(() => ctx.value.merchantId);

  /** 严格单向流转判定:nextIdx === curIdx + 1 才允许。 */
  function canTransitionTo(next: SessionStage): boolean {
    const curIdx = STAGE_ORDER.indexOf(ctx.value.stage);
    const nextIdx = STAGE_ORDER.indexOf(next);
    return nextIdx === curIdx + 1;
  }

  function _replace(next: SessionContext) {
    ctx.value = next;
  }

  async function init(): Promise<void> {
    loading.value = true;
    try {
      _replace(await sessionApi.init());
    } finally {
      loading.value = false;
    }
  }

  async function setZone(zoneId: string): Promise<void> {
    if (!canTransitionTo("ZONE")) throw new Error("invalid transition: ZONE");
    loading.value = true;
    try {
      _replace(await sessionApi.setZone(zoneId));
    } finally {
      loading.value = false;
    }
  }

  async function setCuisine(cuisineId: string): Promise<void> {
    if (!canTransitionTo("CUISINE")) throw new Error("invalid transition: CUISINE");
    loading.value = true;
    try {
      _replace(await sessionApi.setCuisine(cuisineId));
    } finally {
      loading.value = false;
    }
  }

  async function setMerchant(merchantId: string): Promise<void> {
    if (!canTransitionTo("MERCHANT")) throw new Error("invalid transition: MERCHANT");
    loading.value = true;
    try {
      _replace(await sessionApi.setMerchant(merchantId));
    } finally {
      loading.value = false;
    }
  }

  function $reset() {
    ctx.value = { stage: "INIT", zoneId: null, cuisineId: null, merchantId: null };
    loading.value = false;
  }

  return {
    ctx,
    stage,
    zoneId,
    cuisineId,
    merchantId,
    loading,
    canTransitionTo,
    init,
    setZone,
    setCuisine,
    setMerchant,
    $reset,
  };
});