<script setup lang="ts">
function reload(): void {
  window.location.assign('/home')
}
</script>

<template>
  <main class="system-page">
    <section class="system-card">
      <h1>暂时无法访问</h1>
      <p>教学平台当前无法响应，请稍后重试。</p>
      <el-button type="primary" @click="reload">重试</el-button>
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
