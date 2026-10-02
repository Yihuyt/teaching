<script setup lang="ts">
import { Delete } from '@element-plus/icons-vue'

import type { CourseOutlineItemView } from '@/api/generated'
import OutlineDragGrip from '@/features/courses/components/OutlineDragGrip.vue'
import { courseOutlineItemTypeLabels } from '@/shared/labels'

defineProps<{
  item: CourseOutlineItemView
  busy: boolean
  stateClass: Record<string, boolean>
}>()

const emit = defineEmits<{
  open: [item: CourseOutlineItemView]
  results: [item: CourseOutlineItemView]
  remove: [item: CourseOutlineItemView]
  'drag-start': [event: DragEvent, item: CourseOutlineItemView]
  'drag-end': []
  enter: [event: DragEvent, item: CourseOutlineItemView]
  nudge: [item: CourseOutlineItemView, direction: -1 | 1]
  over: [event: DragEvent, item: CourseOutlineItemView]
  leave: []
  drop: [event: DragEvent, item: CourseOutlineItemView]
}>()
</script>

<template>
  <div
    class="item-row"
    :class="stateClass"
    @dragenter="emit('enter', $event, item)"
    @dragover="emit('over', $event, item)"
    @dragleave="emit('leave')"
    @drop="emit('drop', $event, item)"
  >
    <span
      class="drag-handle"
      :class="{ 'drag-handle--disabled': busy }"
      :draggable="!busy"
      role="button"
      :tabindex="busy ? -1 : 0"
      :aria-label="`调整“${item.title}”的顺序`"
      title="拖拽或用方向键调整顺序"
      @dragstart="emit('drag-start', $event, item)"
      @dragend="emit('drag-end')"
      @keydown.up.prevent="!busy && emit('nudge', item, -1)"
      @keydown.down.prevent="!busy && emit('nudge', item, 1)"
    >
      <OutlineDragGrip />
    </span>
    <span class="item-type">{{ courseOutlineItemTypeLabels[item.itemType] }}</span>
    <div class="item-main">
      <div class="item-title-line">
        <button type="button" class="item-open" @click="emit('open', item)">{{ item.title }}</button>
      </div>
    </div>

    <div class="item-actions">
      <el-button
        v-if="item.itemType !== 'material'"
        text
        size="small"
        :disabled="busy"
        @click="emit('results', item)"
      >
        成绩
      </el-button>
      <el-button
        text
        size="small"
        type="danger"
        :icon="Delete"
        :disabled="busy"
        @click="emit('remove', item)"
      >
        移除
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.item-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 46px;
  padding: 4px 16px 4px 40px;
  border-top: 1px solid var(--border);
}

.item-row:hover {
  background: var(--surface-muted);
}

.item-row.drop-before {
  box-shadow: inset 0 2px 0 var(--brand);
}

.item-row.drop-after {
  box-shadow: inset 0 -2px 0 var(--brand);
}

.drag-handle {
  display: inline-flex;
  color: var(--text-muted);
  cursor: grab;
}

.drag-handle--disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.item-type {
  flex-shrink: 0;
  padding: 1px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-secondary);
  font-size: 12px;
}

.item-main {
  flex: 1;
  min-width: 0;
}

.item-open {
  display: block;
  max-width: 100%;
  padding: 0;
  overflow: hidden;
  border: 0;
  color: var(--text);
  background: transparent;
  font-size: 14px;
  font-weight: 600;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}

.item-open:hover {
  color: var(--brand);
}

.item-actions {
  flex-shrink: 0;
}
</style>
