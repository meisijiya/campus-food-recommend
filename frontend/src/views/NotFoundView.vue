<script setup lang="ts">
import { useRouter } from "vue-router";
const router = useRouter();
</script>

<template>
  <div class="max-w-content mx-auto p-6">
    <div class="token-card text-center py-16 space-y-4">
      <h1 class="text-4xl font-semibold text-primary-700">404</h1>
      <p class="text-text-primary font-medium">页面不存在</p>
      <p class="text-sm text-text-secondary">您访问的路径 <code class="font-mono">{{ $route.fullPath }}</code> 不在 demo 范围。</p>
      <button
        type="button"
        class="inline-flex items-center px-4 h-10 rounded-md bg-primary-500 text-text-on-primary text-sm font-medium hover:bg-primary-600"
        @click="router.push('/main')"
      >
        回到主路径
      </button>
    </div>
  </div>
</template>