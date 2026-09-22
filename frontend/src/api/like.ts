/**
 * api/like.ts
 * ---------------------------------------------------------------------------
 * /api/like/{merchantId} 包装。
 * 60s 重复:code=0 message="already liked" liked=false;不当错误,UI 保持。
 * ---------------------------------------------------------------------------
 */
import { api } from "./client";
import type { ApiResponse, LikeResponse } from "@/types/api";

export const likeApi = {
  async like(merchantId: string): Promise<LikeResponse> {
    const { data } = await api.post<ApiResponse<LikeResponse>>(`/api/like/${merchantId}`);
    return data.data;
  },
};