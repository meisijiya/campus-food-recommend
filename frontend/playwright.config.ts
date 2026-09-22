import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright config — F-16.2 slice 6。
 * 假设:
 *  - 后端已在 127.0.0.1:8080 跑(Spring Boot dev profile,MOCK_CATALOG)
 *  - 前端 dev server 在 127.0.0.1:5173(vite)
 *  - Vite 代理 /api → 8080,前端走 baseURL=/
 * 招实习现场旅程 + 限流调试两个 spec。
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
    timeout: 15_000,
  },
  expect: { timeout: 5_000 },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: [
    {
      command: "npm run dev",
      url: "http://127.0.0.1:5173",
      reuseExistingServer: true,
      timeout: 60_000,
      stdout: "ignore",
      stderr: "pipe",
    },
  ],
});