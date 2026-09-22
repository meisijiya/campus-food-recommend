/**
 * tests/stores/auth.spec.ts
 * ---------------------------------------------------------------------------
 * useAuthStore 关键 reducer 覆盖:
 * - login() → 写 access/refresh/user + 持久化 + 推断 role
 * - refresh() → 替换 access/refresh
 * - logout() → 清所有 + localStorage
 * - $reset() → 等价 logout
 * - isAuthenticated / isAdmin getters
 * ---------------------------------------------------------------------------
 */
import { describe, expect, it, vi, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";
import { useAuthStore } from "@/stores/auth";
import * as authApiModule from "@/api/auth";
import type { AuthResponse } from "@/types/api";

const authResponse: AuthResponse = {
  accessToken: "access-1",
  refreshToken: "refresh-1",
  expiresIn: 7200,
};

beforeEach(() => {
  setActivePinia(createPinia());
  localStorage.clear();
});

describe("useAuthStore", () => {
  it("starts with empty state", () => {
    const s = useAuthStore();
    expect(s.accessToken).toBe("");
    expect(s.refreshToken).toBe("");
    expect(s.user).toBeNull();
    expect(s.isAuthenticated).toBe(false);
    expect(s.isAdmin).toBe(false);
  });

  it("login() 推断 USER 角色并写入 store + localStorage", async () => {
    vi.spyOn(authApiModule.authApi, "login").mockResolvedValue(authResponse);
    const s = useAuthStore();
    await s.login("alice", "pwd");

    expect(s.accessToken).toBe("access-1");
    expect(s.refreshToken).toBe("refresh-1");
    expect(s.user).toEqual({ username: "alice", role: "USER" });
    expect(s.isAuthenticated).toBe(true);
    expect(s.isAdmin).toBe(false);
    expect(localStorage.getItem("cfr.auth.accessToken")).toBe("access-1");
    expect(localStorage.getItem("cfr.auth.refreshToken")).toBe("refresh-1");
  });

  it("login() 推断 ADMIN 角色", async () => {
    vi.spyOn(authApiModule.authApi, "login").mockResolvedValue(authResponse);
    const s = useAuthStore();
    await s.login("admin", "pwd");
    expect(s.isAdmin).toBe(true);
    expect(s.user?.role).toBe("ADMIN");
  });

  it("refresh() 替换 token", async () => {
    vi.spyOn(authApiModule.authApi, "refresh").mockResolvedValue({
      ...authResponse,
      accessToken: "access-2",
      refreshToken: "refresh-2",
    });
    const s = useAuthStore();
    const newToken = await s.refresh();
    expect(newToken).toBe("access-2");
    expect(s.accessToken).toBe("access-2");
  });

  it("logout() 清 store + localStorage", () => {
    const s = useAuthStore();
    s.accessToken = "x";
    s.refreshToken = "y";
    s.user = { username: "u", role: "USER" };
    s.logout();
    expect(s.accessToken).toBe("");
    expect(s.user).toBeNull();
    expect(localStorage.getItem("cfr.auth.accessToken")).toBeNull();
  });

  it("$reset() 等价 logout", async () => {
    vi.spyOn(authApiModule.authApi, "login").mockResolvedValue(authResponse);
    const s = useAuthStore();
    await s.login("alice", "pwd");
    s.$reset();
    expect(s.accessToken).toBe("");
    expect(s.user).toBeNull();
  });

  it("init from localStorage 中受信任的 token 不可还原 user 字段", () => {
    localStorage.setItem("cfr.auth.accessToken", "stored-access");
    localStorage.setItem("cfr.auth.refreshToken", "stored-refresh");
    // user 不写,期望 null
    const s = useAuthStore();
    expect(s.accessToken).toBe("stored-access");
    expect(s.user).toBeNull();
    expect(s.isAuthenticated).toBe(true);
  });
});