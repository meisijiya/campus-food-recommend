/**
 * api/session.ts
 * ---------------------------------------------------------------------------
 * F-2 严格单向流转:init → zone → cuisine → merchant。
 * 每个 endpoint 接收 { value: string } 统一 body。
 * ---------------------------------------------------------------------------
 */
import { api } from "./client";
import type { ApiResponse, SessionContext, SlotRequest } from "@/types/api";

export const sessionApi = {
  /** /api/session/init - 拿 SessionContext。MainView.mounted 自动调一次。 */
  async init(): Promise<SessionContext> {
    const { data } = await api.post<ApiResponse<SessionContext>>("/api/session/init");
    return data.data;
  },

  /** /api/session/zone - SlotRequest{ value: zoneId }。 */
  async setZone(zoneId: string): Promise<SessionContext> {
    const body: SlotRequest = { value: zoneId };
    const { data } = await api.post<ApiResponse<SessionContext>>("/api/session/zone", body);
    return data.data;
  },

  /** /api/session/cuisine - SlotRequest{ value: cuisineId }。 */
  async setCuisine(cuisineId: string): Promise<SessionContext> {
    const body: SlotRequest = { value: cuisineId };
    const { data } = await api.post<ApiResponse<SessionContext>>("/api/session/cuisine", body);
    return data.data;
  },

  /** /api/session/merchant - SlotRequest{ value: merchantId }。 */
  async setMerchant(merchantId: string): Promise<SessionContext> {
    const body: SlotRequest = { value: merchantId };
    const { data } = await api.post<ApiResponse<SessionContext>>("/api/session/merchant", body);
    return data.data;
  },
};