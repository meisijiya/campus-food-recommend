<script setup lang="ts">
/**
 * ZoneStepView · F-16.2 主路径第 1 阶:选商圈
 * ---------------------------------------------------------------------------
 * - 拉当前 zoneId 下的商户列表 → 按 zoneId 聚合展示
 * - 点 zone 卡片 → setZone(zoneId) → 跳 CuisineStepView
 * - 边界:zoneId 不存在返 200 data=[] → EmptyState
 * ---------------------------------------------------------------------------
 */
import { ref, computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { useMerchantStore } from "@/stores/merchant";
import { useSessionStore } from "@/stores/session";
import BaseCard from "@/components/BaseCard.vue";
import ErrorState from "@/components/ErrorState.vue";
import EmptyState from "@/components/EmptyState.vue";
import BaseSkeleton from "@/components/BaseSkeleton.vue";

const merchant = useMerchantStore();
const session = useSessionStore();
const router = useRouter();

const ZONES = [
  { id: "Z-E00", name: "东区食堂圈" },
  { id: "Z-E01", name: "西区食堂圈" },
  { id: "Z-E02", name: "南门小吃街" },
  { id: "Z-E03", name: "北门商业街" },
  { id: "Z-E04", name: "中心商圈" },
];

const pickedZoneId = ref<string | null>(null);
const zoneStats = ref<Record<string, number>>({});

const submitting = ref(false);

onMounted(async () => {
  // 拉每个 zone 的列表只为统计 count
  for (const z of ZONES) {
    try {
      const list = await merchant.fetchListByZone(z.id);
      zoneStats.value[z.id] = list.length;
    } catch {
      zoneStats.value[z.id] = 0;
    }
  }
});

const hasError = computed(() => !!merchant.error && Object.keys(zoneStats.value).length === 0);

async function pickZone(zoneId: string) {
  pickedZoneId.value = zoneId;
  submitting.value = true;
  try {
    await session.setZone(zoneId);
    router.push({ name: "main-cuisine" });
  } catch {
    pickedZoneId.value = null;
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <BaseCard title="选择商圈" data-testid="zone-step">
    <ErrorState
      v-if="hasError"
      :message="merchant.error ?? '加载失败'"
      @retry="$router.go(0)"
    />
    <div v-else class="grid grid-cols-2 md:grid-cols-4 gap-3" data-testid="zone-grid">
      <div
        v-for="z in ZONES"
        :key="z.id"
        :class="[
          'p-4 rounded-md border cursor-pointer transition-colors',
          pickedZoneId === z.id
            ? 'border-primary-500 bg-primary-50'
            : 'border-border bg-surface hover:bg-primary-50/50',
        ]"
        :data-testid="`zone-card-${z.id}`"
        @click="pickZone(z.id)"
      >
        <div class="font-medium text-text-primary">{{ z.name }}</div>
        <div class="text-xs text-text-secondary mt-1">
          <BaseSkeleton v-if="zoneStats[z.id] === undefined" :lines="1" />
          <span v-else>{{ zoneStats[z.id] ?? 0 }} 家商户</span>
        </div>
      </div>
    </div>

    <div v-if="submitting" class="mt-3">
      <BaseSkeleton :lines="2" />
    </div>

    <EmptyState
      v-if="!hasError && Object.keys(zoneStats).length > 0 && Object.values(zoneStats).every((v) => v === 0)"
      title="暂无可用商圈"
      message="后端暂无商户数据,请先 seed(参见 backend/scripts/seed-100-merchants.sql)"
    />
  </BaseCard>
</template>