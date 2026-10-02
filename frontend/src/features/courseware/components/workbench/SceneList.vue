<script setup lang="ts">
import { WIDGET_TYPE_LABELS, type Scene } from '@/features/courseware/dsl'
import { stripInline } from '@/features/courseware/layout'

defineProps<{
  scenes: Scene[]
  currentId: string | null
  busy: boolean
}>()

const emit = defineEmits<{
  select: [id: string]
  move: [scene: Scene, direction: -1 | 1]
  remove: [scene: Scene]
  add: []
}>()

function sceneTag(scene: Scene): string {
  if (scene.type === 'quiz') return '测验'
  if (scene.type === 'video') return '视频'
  if (scene.type === 'interactive') {
    return scene.interactive?.widgetType ? WIDGET_TYPE_LABELS[scene.interactive.widgetType] : '交互'
  }
  return ''
}
</script>

<template>
  <nav class="scene-list" aria-label="页面">
    <div
      v-for="(scene, i) in scenes"
      :key="scene.id"
      class="scene-item"
      :class="{ active: scene.id === currentId }"
    >
      <button type="button" class="scene-select" :title="stripInline(scene.title)" @click="emit('select', scene.id)">
        <span class="scene-index">{{ i + 1 }}</span>
        <span class="scene-title">{{ stripInline(scene.title) }}</span>
        <span v-if="sceneTag(scene)" class="scene-tag">{{ sceneTag(scene) }}</span>
      </button>
      <div v-if="scene.id === currentId" class="scene-actions">
        <el-button text size="small" :disabled="busy || i === 0" @click="emit('move', scene, -1)">上移</el-button>
        <el-button text size="small" :disabled="busy || i === scenes.length - 1" @click="emit('move', scene, 1)">
          下移
        </el-button>
        <el-button text size="small" type="danger" :disabled="busy" @click="emit('remove', scene)">删除</el-button>
      </div>
    </div>
    <p v-if="scenes.length === 0" class="scene-empty">还没有页面</p>
    <el-button class="scene-add" :disabled="busy" @click="emit('add')">+ 添加页面</el-button>
  </nav>
</template>

<style scoped>
.scene-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 8px 16px;
}

.scene-item {
  border-radius: var(--radius-control);
}

.scene-item.active {
  background: var(--brand-soft);
}

.scene-select {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  background: transparent;
  font: inherit;
  font-size: 13px;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.scene-item.active .scene-select {
  color: var(--brand);
}

.scene-index {
  flex: none;
  width: 18px;
  color: var(--text-muted);
}

.scene-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scene-tag {
  flex: none;
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: 4px;
  font-size: 11px;
  color: var(--text-muted);
}

.scene-actions {
  display: flex;
  padding: 0 6px 6px 32px;
}

.scene-empty {
  margin: 8px 10px;
  font-size: 12.5px;
  color: var(--text-muted);
}

.scene-add {
  margin: 8px 10px 0;
}
</style>
