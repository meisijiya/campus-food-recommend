import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: "127.0.0.1",
    proxy: {
      "/api":      { target: "http://127.0.0.1:8080", changeOrigin: true },
      "/admin":    { target: "http://127.0.0.1:8080", changeOrigin: true },
      "/actuator": { target: "http://127.0.0.1:8080", changeOrigin: true },
    },
  },
  build: {
    outDir: "dist",
    sourcemap: true,
    target: "es2022",
    // F-16.2 slice 8:分离 vendor,辅助 size-limit 测出真实单 bundle。
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ["vue", "vue-router", "pinia"],
          axios: ["axios"],
        },
      },
    },
  },
});