/**
 * tests/setup.ts
 * ---------------------------------------------------------------------------
 * Vitest happy-dom 测试 setup。在每个 spec 启动时跑。
 * - localStorage 在 happy-dom 已存在,无需 mock
 * ---------------------------------------------------------------------------
 */
import { afterEach, beforeEach } from "vitest";
import { setActivePinia, createPinia } from "pinia";

beforeEach(() => {
  // 每个 test 前重置 Pinia,避免 state 泄露
  setActivePinia(createPinia());
  // 清 localStorage
  localStorage.clear();
});

afterEach(() => {
  // 清理
  setActivePinia(undefined as unknown as ReturnType<typeof createPinia>);
});