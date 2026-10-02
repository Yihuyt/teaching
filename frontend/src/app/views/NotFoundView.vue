<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <main class="system-page">
    <section class="system-card">
      <h1>页面不存在</h1>
      <p>请检查地址，或返回首页继续访问。</p>
      <el-button type="primary" @click="router.replace('/home')">返回首页</el-button>
    </section>
  </main>
</template>

<style scoped>
.system-page {
  display: grid;
  min-height: 100vh;
  place-items: center;
  background: #f5f6f8;
}

.system-card {
  width: 440px;
  padding: 32px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: #fff;
}

h1 {
  margin: 0;
  font-size: 26px;
}

p {
  margin: 12px 0 24px;
  color: var(--text-muted);
  line-height: 1.6;
}
</style>
