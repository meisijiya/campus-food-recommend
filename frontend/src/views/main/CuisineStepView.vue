<script setup lang="ts">
/**
 * CuisineStepView · F-16.2 主路径第 2 阶:选菜系
 * ---------------------------------------------------------------------------
 * - 拉当前 zoneId 下的商户 → 按 cuisineId 聚合
 * - 点 cuisine → setCuisine(cuisineId) → 跳 RecommendView
 * ---------------------------------------------------------------------------
 */
import { ref, computed, onMounted, watch } from "vue";
import { useRouter } from "vue-router";
import { useMerchantStore } from "@/stores/merchant";
import { useSessionStore } from "@/stores/session";
import BaseCard from "@/components/BaseCard.vue";
import ErrorState from "@/components/ErrorState.vue";
import EmptyState from "@/components/EmptyState.vue";
import BaseSkeleton from "@/components/BaseSkeleton.vue";
import type { Merchant } from "@/types/api";

const merchant = useMerchantStore();
const session = useSessionStore();
const router = useRouter();

const list = ref<Merchant[]>([]);
const loading = ref(false);
const errorMsg = ref<string | null>(null);
const submitting = ref(false);

const pickedCuisineId = ref<string | null>(null);

interface CuisineGroup {
  id: string;
  label: string;
  count: number;
}

const CUISINE_LABELS: Record<string, string> = {
  C001: "面食",
  C002: "米饭快餐",
  C003: "麻辣烫",
  C004: "烧烤",
  C005: "奶茶甜品",
  C006: "西餐",
  C007: "其他",
};

const cuisines = computed<CuisineGroup[]>(() => {
  const map = new Map<string, number>();
  for (const m of list.value) {
    map.set(m.cuisineId, (map.get(m.cuisineId) ?? 0) + 1);
  }
  return Array.from(map.entries()).map(([id, count]) => ({
    id,
    label: CUISINE_LABELS[id] ?? id,
    count,
  }));
});

async function load() {
  if (!session.zoneId) {
    errorMsg.value = "未选择 zone";
    return;
  }
  loading.value = true;
  errorMsg.value = null;
  try {
    list.value = await merchant.fetchListByZone(session.zoneId);
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

onMounted(load);
watch(() => session.zoneId, load);

async function pickCuisine(cuisineId: string) {
  pickedCuisineId.value = cuisineId;
  submitting.value = true;
  try {
    await session.setCuisine(cuisineId);
    router.push({ name: "main-recommend" });
  } catch {
    pickedCuisineId.value = null;
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <BaseCard title="选择菜系" data-testid="cuisine-step">
    <ErrorState
      v-if="errorMsg"
      :message="errorMsg"
      @retry="load"
    />
    <div v-else-if="loading" class="space-y-2">
      <BaseSkeleton :lines="4" />
    </div>
    <div v-else-if="cuisines.length === 0">
      <EmptyState title="该商圈暂无商户" message="请换一个商圈" />
    </div>
    <div v-else class="grid grid-cols-2 md:grid-cols-3 gap-3" data-testid="cuisine-grid">
      <div
        v-for="c in cuisines"
        :key="c.id"
        :class="[
          'p-4 rounded-md border cursor-pointer transition-colors',
          pickedCuisineId === c.id
            ? 'border-primary-500 bg-primary-50'
            : 'border-border bg-surface hover:bg-primary-50/50',
        ]"
        :data-testid="`cuisine-card-${c.id}`"
        @click="pickCuisine(c.id)"
      >
        <div class="font-medium text-text-primary">{{ c.label }}</div>
        <div class="text-xs text-text-secondary mt-1">{{ c.count }} 家商户</div>
      </div>
    </div>

    <div v-if="submitting" class="mt-3">
      <BaseSkeleton :lines="2" />
    </div>
  </BaseCard>
</template>