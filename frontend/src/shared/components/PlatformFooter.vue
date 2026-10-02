<script setup lang="ts">
import { usePlatformStore } from '@/stores/platform'

const platform = usePlatformStore()
</script>

<template>
  <footer class="platform-footer">{{ platform.footerText }}</footer>
</template>

<style scoped>
.platform-footer {
  display: grid;
  min-height: 40px;
  padding: 10px var(--page-gutter);
  place-items: center;
  color: var(--text-muted);
  background: transparent;
  font-size: 12px;
  text-align: center;
}
</style>
