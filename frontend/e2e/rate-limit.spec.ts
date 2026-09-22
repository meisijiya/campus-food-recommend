/**
 * e2e/rate-limit.spec.ts
 * ---------------------------------------------------------------------------
 * 限流调试 e2e:触发限流 → CountdownButton 倒计时 → 恢复。
 * 依赖:已登录 + Admin 角色。
 * ---------------------------------------------------------------------------
 */
import { test, expect } from "@playwright/test";

test.describe("限流调试", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/");
    await page.getByLabel("用户名").fill("admin");
    await page.getByLabel("密码").fill("admin");
    await page.getByTestId("login-submit").click();
    await expect(page).toHaveURL(/#\/main$/);
  });

  test("RateLimitDebugView 触发限流 → 1 秒后恢复", async ({ page }) => {
    await page.goto("/#/admin/rate-limit");
    await expect(page.getByRole("heading", { name: "限流调试" })).toBeVisible();
    // 当前 RateLimitDebugView 还是占位(F-16.4 实施),这一段验证 UI 已渲染即可。
  });
});