/**
 * lib/env.ts
 * ---------------------------------------------------------------------------
 * 类型化 import.meta.env,集中管理 VITE_* 变量。
 * 默认走 "/" 走 Vite 代理或 Nginx 80;如要直连后端,在启动时设 VITE_API_BASE=http://127.0.0.1:8080。
 * ---------------------------------------------------------------------------
 */

export const env = {
  apiBase: import.meta.env.VITE_API_BASE ?? "/",
  debug: import.meta.env.VITE_DEBUG === "true",
} as const;