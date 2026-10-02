<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'

import type { SaveState } from '@/features/blockcoding/store'

const props = defineProps<{
  projectName: string
  saveState: SaveState
  back: RouteLocationRaw
  backLabel: string
}>()

const emit = defineEmits<{
  save: []
}>()

const router = useRouter()

const saveLabel = computed(() => {
  switch (props.saveState) {
    case 'saved':
      return '已保存'
    case 'dirty':
      return '有未保存的修改'
    case 'saving':
      return '保存中…'
  }
  return ''
})
</script>

<template>
  <div class="studio-toolbar">
    <el-button text @click="router.push(props.back)">{{ backLabel }}</el-button>
    <strong class="studio-toolbar__name">{{ projectName }}</strong>
    <span class="studio-toolbar__state" :class="`studio-toolbar__state--${saveState}`">
      {{ saveLabel }}
    </span>
    <el-button type="primary" size="small" :loading="saveState === 'saving'" @click="emit('save')">
      保存
    </el-button>
  </div>
</template>

<style scoped>
.studio-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  height: 48px;
  padding: 0 16px;
  border-bottom: 1px solid var(--el-border-color-light);
  background: #fff;
}

.studio-toolbar__name {
  max-width: 320px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.studio-toolbar__state {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.studio-toolbar__state--dirty {
  color: var(--el-color-warning);
}
</style>
