/**
 * api/client.ts
 * ---------------------------------------------------------------------------
 * axios 实例 + 拦截器:
 *  - 请求拦截:自动加 Bearer 头(从 useAuthStore 取)
 *  - 响应拦截:
 *    * 429:抛给调用方处理(CountdownButton)
 *    * 401:自动 refresh,串行化(防止并发重复 refresh)
 *    * 业务错误 code != 0:toast(默认;silent=true 跳过)
 * ---------------------------------------------------------------------------
 */
import axios, { AxiosError, type AxiosRequestConfig } from "axios";
import { env } from "@/lib/env";
import { ApiErrorCode } from "@/types/api";

export interface ApiAxiosRequestConfig extends AxiosRequestConfig {
  /** 已重试标记,防止 refresh 死循环。 */
  _retried?: boolean;
  /** 静默请求:跳过自动 toast,通常用于 polling。 */
  silent?: boolean;
  /** 不会走 refresh 的脚本(登录 / refresh 本身)。 */
  skipAuth?: boolean;
}

const api = axios.create({
  baseURL: env.apiBase,
  timeout: 10_000,
  headers: { "Content-Type": "application/json" },
});

/** 动态注入拦截器,避免循环 import。 */
export function installInterceptors(getAuth: () => {
  accessToken: string;
  refreshToken: string;
  refresh: () => Promise<string>;
  logout: () => void;
}, getUi: () => { toast: (t: { type: "success" | "warning" | "error" | "info"; message: string; duration?: number }) => void }) {
  // 请求拦截:加 Authorization
  api.interceptors.request.use((cfg) => {
    const config = cfg as ApiAxiosRequestConfig;
    if (config.skipAuth) return cfg;
    const auth = getAuth();
    if (auth.accessToken && !config.headers?.Authorization) {
      config.headers = config.headers ?? {};
      (config.headers as Record<string, string>).Authorization = `Bearer ${auth.accessToken}`;
    }
    return cfg;
  });

  // 响应拦截:统一错误处理
  let refreshing: Promise<string> | null = null;

  api.interceptors.response.use(
    (r) => r,
    async (err: AxiosError<{ code?: number; message?: string }>) => {
      const ui = getUi();
      const status = err.response?.status;
      const config = err.config as ApiAxiosRequestConfig | undefined;

      // 1. 限流 → 让调用方接 throw,只 toast 提示
      if (status === 429) {
        const retryAfter = Number(err.response?.headers?.["retry-after"] ?? 1);
        ui.toast({
          type: "warning",
          message: `请求过快,${retryAfter} 秒后重试`,
          duration: retryAfter * 1000,
        });
        throw err;
      }

      // 2. 401 自动 refresh
      if (status === 401 && config && !config._retried && !config.skipAuth) {
        config._retried = true;
        const auth = getAuth();
        try {
          if (!auth.refreshToken) {
            // 无 refreshToken,直接跳登录
            auth.logout();
            window.location.hash = "/login";
            throw err;
          }
          refreshing ??= auth.refresh();
          await refreshing;
          refreshing = null;
          config.headers = config.headers ?? {};
          (config.headers as Record<string, string>).Authorization = `Bearer ${getAuth().accessToken}`;
          return api.request(config);
        } catch (refreshErr) {
          refreshing = null;
          getAuth().logout();
          window.location.hash = "/login";
          throw refreshErr;
        }
      }

      // 3. 业务错误统一 toast(除非 silent)
      const code = err.response?.data?.code;
      if (code !== undefined && code !== ApiErrorCode.OK && !config?.silent) {
        ui.toast({
          type: "error",
          message: err.response?.data?.message ?? "请求失败",
        });
      }

      throw err;
    },
  );
}

export { api };