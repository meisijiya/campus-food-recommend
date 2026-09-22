/**
 * api/preheat.ts
 * ---------------------------------------------------------------------------
 * /admin/preheat/trigger 包装(ADMIN 角色)。
 * ---------------------------------------------------------------------------
 */
import { api } from "./client";
import type { ApiResponse, PreheatResult } from "@/types/api";

export const preheatApi = {
  async trigger(): Promise<PreheatResult> {
    const { data } = await api.post<ApiResponse<PreheatResult>>("/admin/preheat/trigger");
    return data.data;
  },
};