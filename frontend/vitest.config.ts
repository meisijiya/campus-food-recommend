/// <reference types="vitest" />
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";

// https://vitest.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    environment: "happy-dom",
    globals: false,
    include: ["src/**/*.{test,spec}.ts", "tests/**/*.spec.ts"],
    exclude: ["node_modules", "dist", "e2e"],
    setupFiles: ["./tests/setup.ts"],
    coverage: {
      provider: "v8",
      reporter: ["text", "html"],
      // F-16.2 slice 5:5 个关键 store + recommend 纯函数 ≥ 80% 覆盖。
      // (ui/flag 不在 ticket 5 范围内,scope 由 ADR-0013 后续 ticket 覆盖)
      include: [
        "src/stores/auth.ts",
        "src/stores/session.ts",
        "src/stores/recommend.ts",
        "src/stores/like.ts",
        "src/stores/merchant.ts",
        "src/api/recommend.ts",
      ],
      thresholds: {
        lines: 80,
        functions: 80,
        branches: 70,
        statements: 80,
      },
    },
  },
});