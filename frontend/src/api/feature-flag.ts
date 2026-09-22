/**
 * api/feature-flag.ts
 * ---------------------------------------------------------------------------
 * Public + Admin 两类 FeatureFlag 端点。
 * Public 不需要鉴权,Admin 需要 ADMIN 角色。
 * ---------------------------------------------------------------------------
 */
import { api } from "./client";
import type {
  ApiResponse,
  FlagCheckResult,
  FlagConfig,
  FlagConfigUpdate,
} from "@/types/api";

export const featureFlagApi = {
  /** Public: /api/feature-flag/{key}/check */
  async check(flagKey: string, studentId: number): Promise<FlagCheckResult> {
    const { data } = await api.get<ApiResponse<FlagCheckResult>>(
      `/api/feature-flag/${flagKey}/check`,
      { params: { studentId } },
    );
    return data.data;
  },

  /** Admin: /admin/feature-flag/{key} GET */
  async getConfig(flagKey: string): Promise<FlagConfig> {
    const { data } = await api.get<ApiResponse<FlagConfig>>(`/admin/feature-flag/${flagKey}`);
    return data.data;
  },

  /** Admin: /admin/feature-flag/{key} POST */
  async setConfig(flagKey: string, body: FlagConfigUpdate): Promise<FlagConfig> {
    const { data } = await api.post<ApiResponse<FlagConfig>>(
      `/admin/feature-flag/${flagKey}`,
      body,
    );
    return data.data;
  },

  /** Admin: /admin/feature-flag 列表(后端文档未列精确字段名,后端为 List<Map<String,Object>> 风格时由 store 适配)。 */
  async listAll(): Promise<Record<string, FlagConfig>> {
    const { data } = await api.get<ApiResponse<Record<string, FlagConfig>>>(
      "/admin/feature-flag",
    );
    return data.data ?? {};
  },
};