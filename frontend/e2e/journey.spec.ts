/**
 * e2e/journey.spec.ts
 * ---------------------------------------------------------------------------
 * 招实习现场旅程 e2e:登录 → 主路径 → 点赞 → 商家详情 → 退出。
 * 依赖:Spring Boot 后端在 127.0.0.1:8080 + Vite dev server 自动启动。
 * locator 优先级按 Playwright 官方建议:getByRole > getByLabel > getByText。
 * ---------------------------------------------------------------------------
 */
import { test, expect } from "@playwright/test";

test.describe("招实习现场主路径旅程", () => {
  test("登录 → 主路径 → 点赞 → 详情 → 退出", async ({ page }) => {
    await page.goto("/");

    // 1. 登录(后端 UserDetailsServiceImpl 内置 demo / demo)
    await page.getByLabel("用户名").fill("demo");
    await page.getByLabel("密码").fill("demo");
    await page.getByTestId("login-submit").click();

    // 登录成功后跳到 /main
    await expect(page).toHaveURL(/#\/main$/);

    // 2. 选 zone(默认进入 ZoneStepView)
    await expect(page.getByTestId("zone-step")).toBeVisible();
    const firstZone = page.getByTestId(/^zone-card-Z/);
    await expect(firstZone.first()).toBeVisible({ timeout: 10_000 });
    await firstZone.first().click();

    // 3. 选 cuisine
    await expect(page.getByTestId("cuisine-step")).toBeVisible({ timeout: 10_000 });
    const firstCuisine = page.getByTestId(/^cuisine-card-C/);
    await expect(firstCuisine.first()).toBeVisible();
    await firstCuisine.first().click();

    // 4. 推荐结果页 + hit_tier 角标
    await expect(page.getByTestId("recommend-step")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/mock|dashscope|fallback/i).first()).toBeVisible();

    // 5. 点赞第一个商户(乐观更新)
    const merchantCard = page.getByTestId(/^merchant-card-/);
    const merchantId = await merchantCard.first().getAttribute("data-testid");
    expect(merchantId).toMatch(/^merchant-card-[A-Za-z0-9-]+$/);
    const likeBtn = page.getByTestId(`like-btn-${merchantId!.replace("merchant-card-", "")}`);
    await likeBtn.click();
    await expect(likeBtn).toContainText("❤️");

    // 6. 打开商家详情
    await merchantCard.first().click();
    await expect(page.getByTestId("merchant-detail")).toBeVisible({ timeout: 10_000 });

    // 7. 退出登录
    await page.getByTestId("logout-btn").click();
    await expect(page).toHaveURL(/#\/login$/);
  });
});