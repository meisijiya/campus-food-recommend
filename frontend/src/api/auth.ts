/**
 * api/auth.ts
 * ---------------------------------------------------------------------------
 * /api/auth/login + /api/auth/refresh 包装。
 * ---------------------------------------------------------------------------
 */
import { api, type ApiAxiosRequestConfig } from "./client";
import type { ApiResponse, AuthResponse, LoginRequest, RefreshRequest } from "@/types/api";

export const authApi = {
  async login(body: LoginRequest): Promise<AuthResponse> {
    const cfg: ApiAxiosRequestConfig = { skipAuth: true };
    const { data } = await api.post<ApiResponse<AuthResponse>>("/api/auth/login", body, cfg);
    return data.data;
  },

  async refresh(refreshToken: string): Promise<AuthResponse> {
    const cfg: ApiAxiosRequestConfig = { skipAuth: true };
    const body: RefreshRequest = { refreshToken };
    const { data } = await api.post<ApiResponse<AuthResponse>>("/api/auth/refresh", body, cfg);
    return data.data;
  },
};