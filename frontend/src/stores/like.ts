/**
 * stores/like.ts
 * ---------------------------------------------------------------------------
 * 点赞状态 + 乐观更新。
 * - 立即翻转 UI(无 loading 视觉延迟)
 * - 请求失败回滚
 * - 60s 重复:liked=false 不当错误,UI 保持(让 .catch 不抛)
 * ---------------------------------------------------------------------------
 */
import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { likeApi } from "@/api/like";

export const useLikeStore = defineStore("like", () => {
  const likedSet = ref<Set<string>>(new Set());
  const pendingIds = ref<Set<string>>(new Set());

  function isLiked(merchantId: string): boolean {
    return likedSet.value.has(merchantId);
  }

  function isPending(merchantId: string): boolean {
    return pendingIds.value.has(merchantId);
  }

  const likedCount = computed(() => likedSet.value.size);

  async function toggle(merchantId: string): Promise<void> {
    const was = likedSet.value.has(merchantId);
    // 1. 立即翻转 UI
    if (was) likedSet.value.delete(merchantId);
    else likedSet.value.add(merchantId);

    try {
      // 2. 发请求;失败回滚
        const r = await likeApi.like(merchantId);
        // 服务端权威值覆盖(如 60s already-liked)
        if (r.liked) likedSet.value.add(merchantId);
        else likedSet.value.delete(merchantId);
    } catch (e) {
      // 回滚
      if (was) likedSet.value.add(merchantId);
      else likedSet.value.delete(merchantId);
      throw e;
    } finally {
      pendingIds.value.delete(merchantId);
    }
  }

  function $reset() {
    likedSet.value = new Set();
    pendingIds.value = new Set();
  }

  return { likedSet, pendingIds, likedCount, isLiked, isPending, toggle, $reset };
});