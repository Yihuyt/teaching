<script setup lang="ts">
import { Document, WarningFilled } from '@element-plus/icons-vue'

defineProps<{
  loading: boolean
  error?: string
  empty: boolean
  emptyText?: string
}>()

defineEmits<{
  retry: []
}>()
</script>

<template>
  <div v-if="loading" class="state-box state-box--loading" aria-live="polite">
    <el-skeleton :rows="3" animated />
  </div>
  <div v-else-if="error" class="state-box state-box--error" role="alert">
    <el-icon class="state-box__icon"><WarningFilled /></el-icon>
    <div class="state-box__message">
      <strong>加载失败</strong>
      <p>{{ error }}</p>
    </div>
    <el-button size="small" @click="$emit('retry')">重新加载</el-button>
  </div>
  <div v-else-if="empty" class="state-box state-box--empty" role="status">
    <el-icon class="state-box__icon"><Document /></el-icon>
    <span>{{ emptyText ?? '暂无数据' }}</span>
  </div>
  <slot v-else />
</template>

<style scoped>
.state-box {
  color: var(--text-muted);
  font-size: 14px;
}

.state-box--loading {
  padding: 20px 0;
}

.state-box--error,
.state-box--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 104px;
  padding: 20px;
}

.state-box--error {
  gap: 12px;
}

.state-box--empty {
  gap: 8px;
}

.state-box__icon {
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: 18px;
}

.state-box--error .state-box__icon {
  color: var(--danger);
}

.state-box__message {
  max-width: 560px;
  min-width: 0;
}

.state-box__message strong {
  color: var(--text);
  font-weight: 600;
}

.state-box__message p {
  margin: 2px 0 0;
  overflow-wrap: anywhere;
}
</style>
