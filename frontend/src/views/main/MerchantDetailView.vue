<script setup lang="ts">
/**
 * MerchantDetailView · F-16.2 商家详情
 * ---------------------------------------------------------------------------
 * - /api/merchant/{id} 拉详情
 * - 点赞大按钮(乐观更新)
 * - 404 ErrorState
 * ---------------------------------------------------------------------------
 */
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useMerchantStore } from "@/stores/merchant";
import { useLikeStore } from "@/stores/like";
import BaseCard from "@/components/BaseCard.vue";
import BaseButton from "@/components/BaseButton.vue";
import BaseSkeleton from "@/components/BaseSkeleton.vue";
import ErrorState from "@/components/ErrorState.vue";
import type { Merchant } from "@/types/api";

const route = useRoute();
const router = useRouter();
const merchant = useMerchantStore();
const like = useLikeStore();

const id = computed(() => (typeof route.params.id === "string" ? route.params.id : ""));
const detail = ref<Merchant | null>(null);
const loading = ref(false);
const errorMsg = ref<string | null>(null);
const notFound = ref(false);

async function load() {
  if (!id.value) return;
  loading.value = true;
  errorMsg.value = null;
  notFound.value = false;
  try {
    detail.value = await merchant.fetchById(id.value);
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e);
    if (errorMsg.value.includes("404")) notFound.value = true;
  } finally {
    loading.value = false;
  }
}

onMounted(load);
watch(id, load);

async function onLike() {
  if (!detail.value) return;
  try {
    await like.toggle(detail.value.id);
  } catch {
    /* 拦截器已 toast */
  }
}

function backToRecommend() {
  router.push({ name: "main-recommend" });
}
</script>

<template>
  <BaseCard title="商家详情" data-testid="merchant-detail">
    <ErrorState
      v-if="notFound"
      title="商家不存在"
      message="该 merchantId 没有对应数据,可能已被下架"
      @retry="backToRecommend"
    >
      <template #actions>
        <BaseButton variant="secondary" size="sm" @click="backToRecommend">
          返回推荐
        </BaseButton>
      </template>
    </ErrorState>
    <ErrorState
      v-else-if="errorMsg && !notFound"
      :message="errorMsg"
      @retry="load"
    />
    <div v-else-if="loading || !detail" class="space-y-2">
      <BaseSkeleton :lines="6" />
    </div>
    <template v-else>
      <div class="space-y-3">
        <div>
          <h2 class="text-xl font-semibold text-text-primary">{{ detail.name }}</h2>
          <p class="text-xs text-text-secondary mt-1">
            ID {{ detail.id }} · 热度 {{ detail.heatScore?.toFixed(1) ?? "—" }}
          </p>
        </div>
        <p v-if="detail.tags" class="text-sm">{{ detail.tags }}</p>
        <div class="text-xs text-text-secondary">
          zoneId: {{ detail.zoneId }} · cuisineId: {{ detail.cuisineId }}
        </div>
        <div class="pt-2">
          <BaseButton
            :variant="like.isLiked(detail.id) ? 'danger' : 'primary'"
            size="lg"
            block
            :data-testid="`like-btn-${detail.id}`"
            @click="onLike"
          >
            {{ like.isLiked(detail.id) ? "❤️ 已点赞" : "🤍 点赞" }}
          </BaseButton>
        </div>
      </div>
    </template>
  </BaseCard>
</template>