<script setup lang="ts">
/**
 * RecommendView · F-16.2 主路径第 3 阶:推荐结果
 * ---------------------------------------------------------------------------
 * - 调 /api/recommend,展示 hit_tier + token 数 + 推荐理由
 * - 3-5 张商户卡(含点赞按钮 + 跳详情)
 * - "重新推荐" 按钮
 * ---------------------------------------------------------------------------
 */
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useRecommendStore } from "@/stores/recommend";
import { useMerchantStore } from "@/stores/merchant";
import { useLikeStore } from "@/stores/like";
import { useSessionStore } from "@/stores/session";
import BaseCard from "@/components/BaseCard.vue";
import BaseButton from "@/components/BaseButton.vue";
import BaseSkeleton from "@/components/BaseSkeleton.vue";
import ErrorState from "@/components/ErrorState.vue";
import HitTierBadge from "@/components/HitTierBadge.vue";
import TokenCounter from "@/components/TokenCounter.vue";

const rec = useRecommendStore();
const merchant = useMerchantStore();
const like = useLikeStore();
const session = useSessionStore();
const router = useRouter();

const merchants = ref<{ id: string; name: string; tags: string | null; heatScore: number | null }[]>(
  [],
);

const confidencePct = computed(() => Math.round((rec.confidenceMeter ?? 0) * 100));

async function load(forceRegenerate = false) {
  await rec.recommend(forceRegenerate);
  // 拿推荐 id 列表 → 从 store 拿详情
  for (const id of rec.merchantIds) {
    if (merchant.getById(id)) continue;
    try {
      await merchant.fetchById(id);
    } catch {
      // 单个失败忽略
    }
  }
  merchants.value = rec.merchantIds
    .map((id) => merchant.getById(id))
    .filter((m): m is NonNullable<typeof m> => m !== undefined);
}

onMounted(() => {
  if (rec.merchantIds.length === 0) {
    load(false);
  } else {
    merchants.value = rec.merchantIds
      .map((id) => merchant.getById(id))
      .filter((m): m is NonNullable<typeof m> => m !== undefined);
  }
});

async function onLike(id: string, ev: Event) {
  ev.stopPropagation();
  try {
    await like.toggle(id);
  } catch {
    /* 拦截器已 toast */
  }
}

function openDetail(id: string) {
  router.push({ name: "main-merchant-detail", params: { id } });
}

function backToCuisine() {
  router.push({ name: "main-cuisine" });
}

const isMerchantStage = computed(() => session.stage === "MERCHANT");
</script>

<template>
  <BaseCard title="推荐结果" data-testid="recommend-step">
    <template #actions>
      <div class="flex items-center gap-2 text-xs text-text-secondary">
        <HitTierBadge :tier="rec.hitTier" />
        <TokenCounter :prompt="rec.promptTokens" :completion="rec.completionTokens" />
      </div>
    </template>

    <ErrorState
      v-if="rec.error"
      :message="rec.error"
      @retry="() => load(true)"
    />
    <div v-else-if="rec.loading && !rec.lastResult" class="space-y-2">
      <div class="text-sm text-text-secondary mb-2">AI 思考中...</div>
      <BaseSkeleton :lines="6" />
    </div>
    <template v-else>
      <!-- 推荐理由 -->
      <div class="mb-4 p-3 rounded-md bg-secondary-50 border border-secondary-100">
        <div class="text-sm font-medium text-secondary-700 mb-1">推荐理由</div>
        <div class="text-sm text-text-primary">{{ rec.reason || "(无理由)" }}</div>
        <div class="text-xs text-text-secondary mt-2">置信度: {{ confidencePct }}%</div>
      </div>

      <!-- 商户卡 -->
      <div v-if="merchants.length === 0" class="space-y-2">
        <BaseSkeleton :lines="4" />
      </div>
      <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3" data-testid="merchant-list">
        <article
          v-for="m in merchants"
          :key="m.id"
          :data-testid="`merchant-card-${m.id}`"
          class="rounded-md border border-border bg-surface p-3 hover:bg-primary-50/30 cursor-pointer transition-colors"
          @click="openDetail(m.id)"
        >
          <header class="flex items-start justify-between gap-2">
            <div>
              <h3 class="font-medium text-text-primary">{{ m.name }}</h3>
              <p class="text-xs text-text-secondary mt-0.5">
                热度 {{ m.heatScore?.toFixed(1) ?? "—" }}
              </p>
            </div>
            <button
              type="button"
              class="text-lg"
              :aria-pressed="like.isLiked(m.id)"
              :data-testid="`like-btn-${m.id}`"
              @click="onLike(m.id, $event)"
            >
              {{ like.isLiked(m.id) ? "❤️" : "🤍" }}
            </button>
          </header>
          <p v-if="m.tags" class="text-xs text-text-secondary mt-2">{{ m.tags }}</p>
        </article>
      </div>

      <div class="flex items-center justify-between mt-4">
        <BaseButton variant="ghost" size="sm" @click="backToCuisine">
          ← 重选菜系
        </BaseButton>
        <BaseButton
          variant="secondary"
          size="sm"
          :loading="rec.loading"
          data-testid="regenerate-btn"
          @click="() => load(true)"
        >
          重新推荐
        </BaseButton>
      </div>

      <p v-if="isMerchantStage" class="mt-2 text-xs text-success-500">
        ✓ 已写入 session.merchantId(后端 F-2 槽位记录)
      </p>
    </template>
  </BaseCard>
</template>