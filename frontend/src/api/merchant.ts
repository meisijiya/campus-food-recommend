/**
 * api/merchant.ts
 * ---------------------------------------------------------------------------
 * /api/merchant/{id} getById + /api/merchant?zoneId= listByZone。
 * zoneId 不存在返 200 data=[] 不 404,前端接空态,不弹错。
 * ---------------------------------------------------------------------------
 */
import { api } from "./client";
import type { ApiResponse, Merchant } from "@/types/api";

export const merchantApi = {
  async getById(id: string): Promise<Merchant> {
    const { data } = await api.get<ApiResponse<Merchant>>(`/api/merchant/${id}`);
    return data.data;
  },

  async listByZone(zoneId: string): Promise<Merchant[]> {
    const { data } = await api.get<ApiResponse<Merchant[]>>("/api/merchant", {
      params: { zoneId },
    });
    return data.data ?? [];
  },
};