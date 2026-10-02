<script setup lang="ts">
import { ref } from 'vue'
import { ArrowDown, ArrowRight, Delete, Edit, FolderAdd, Plus } from '@element-plus/icons-vue'

import type { CourseOutlineUnitView, CourseOutlineItemView } from '@/api/generated'
import OutlineDragGrip from '@/features/courses/components/OutlineDragGrip.vue'
import OutlineItemRow from '@/features/courses/components/OutlineItemRow.vue'
import { placeAfter, useOutlineItemDnd } from '@/features/courses/useOutlineItemDnd'
import { outlineDrag } from '@/features/courses/outlineDrag'

const props = defineProps<{
  unit: CourseOutlineUnitView
  parentId: number | null
  depth: number
  busy: boolean
}>()

const emit = defineEmits<{
  'add-item': [unit: CourseOutlineUnitView]
  'create-child': [parentId: number]
  'edit-unit': [unit: CourseOutlineUnitView]
  'remove-unit': [unit: CourseOutlineUnitView]
  'open-item': [item: CourseOutlineItemView]
  'results-item': [item: CourseOutlineItemView]
  'remove-item': [item: CourseOutlineItemView]
  'reorder-unit': [unitId: number, targetUnitId: number, after: boolean]
  /** 本单元的键盘调序请求:同级数组在父级手里 */
  'nudge-unit': [unitId: number, direction: -1 | 1]
  /** 把 itemId 移到本组 targetItemId 之前 / 之后;targetItemId 为 null = 追加到组末尾 */
  'reorder-item': [unitId: number, itemId: number, targetItemId: number | null, after: boolean]
}>()

const expanded = ref(true)

function nudgeItem(item: CourseOutlineItemView, direction: -1 | 1): void {
  const siblings = props.unit.items
  const target = siblings[siblings.findIndex((candidate) => candidate.id === item.id) + direction]
  if (target) emit('reorder-item', props.unit.id, item.id, target.id, direction > 0)
}

function nudgeChildUnit(unitId: number, direction: -1 | 1): void {
  const siblings = props.unit.children
  const target = siblings[siblings.findIndex((candidate) => candidate.id === unitId) + direction]
  if (target) emit('reorder-unit', unitId, target.id, direction > 0)
}
const unitDropTarget = ref<{ id: number; after: boolean } | null>(null)
const itemDropInto = ref(false)
const itemDnd = useOutlineItemDnd(
  () => props.unit.id,
  () => props.busy,
  (itemId, targetItemId, after) => emit('reorder-item', props.unit.id, itemId, targetItemId, after),
)

function startUnitDrag(event: DragEvent): void {
  if (props.busy) {
    event.preventDefault()
    return
  }
  outlineDrag.current = { kind: 'unit', id: props.unit.id, parentId: props.parentId }
  event.dataTransfer?.setData('text/plain', `unit:${props.unit.id}`)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}

function endDrag(): void {
  outlineDrag.current = null
  unitDropTarget.value = null
  itemDropInto.value = false
}

function acceptsUnitDrop(): boolean {
  const dragging = outlineDrag.current
  return (
    dragging !== null &&
    dragging.kind === 'unit' &&
    dragging.parentId === props.parentId &&
    dragging.id !== props.unit.id
  )
}

function overUnit(event: DragEvent): void {
  if (outlineDrag.current?.kind === 'item') {
    event.preventDefault()
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
    itemDropInto.value = true
    return
  }
  if (!acceptsUnitDrop()) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  unitDropTarget.value = { id: props.unit.id, after: placeAfter(event) }
}

/** dragenter / dragleave 在子元素间移动时成对触发:计数归零才算真正离开 */
let hovering = 0

function enterUnit(event: DragEvent): void {
  hovering += 1
  overUnit(event)
}

function leaveUnit(): void {
  hovering = Math.max(0, hovering - 1)
  if (hovering > 0) return
  unitDropTarget.value = null
  itemDropInto.value = false
}

function dropOnUnit(event: DragEvent): void {
  hovering = 0
  const dragging = outlineDrag.current
  if (dragging?.kind === 'item') {
    event.preventDefault()
    expanded.value = true
    emit('reorder-item', props.unit.id, dragging.id, null, true)
    endDrag()
    return
  }
  if (!acceptsUnitDrop() || dragging === null) return
  event.preventDefault()
  emit('reorder-unit', dragging.id, props.unit.id, placeAfter(event))
  endDrag()
}

function unitDropClass(): Record<string, boolean> {
  const target = unitDropTarget.value
  const active = target !== null && target.id === props.unit.id
  return {
    'drop-before': active && !target.after,
    'drop-after': active && target.after,
    'drop-into': itemDropInto.value,
  }
}
</script>

<template>
  <article
    class="unit-node"
    :class="depth === 0 ? 'unit-node--root' : 'unit-node--nested'"
    :data-depth="depth"
  >
    <header
      class="unit-row"
      :class="unitDropClass()"
      @dragenter="enterUnit"
      @dragover="overUnit"
      @dragleave="leaveUnit"
      @drop="dropOnUnit"
    >
      <span
        class="drag-handle"
        :class="{ 'drag-handle--disabled': busy }"
        :draggable="!busy"
        role="button"
        :tabindex="busy ? -1 : 0"
        :aria-label="`调整“${unit.title}”的顺序`"
        title="拖动排序"
        @dragstart="startUnitDrag"
        @dragend="endDrag"
        @keydown.up.prevent="!busy && emit('nudge-unit', unit.id, -1)"
        @keydown.down.prevent="!busy && emit('nudge-unit', unit.id, 1)"
      >
        <OutlineDragGrip />
      </span>
      <button
        type="button"
        class="unit-toggle"
        :aria-label="expanded ? `收起单元：${unit.title}` : `展开单元：${unit.title}`"
        :aria-expanded="expanded"
        @click="expanded = !expanded"
      >
        <el-icon><ArrowDown v-if="expanded" /><ArrowRight v-else /></el-icon>
      </button>

      <div class="unit-main">
        <div class="unit-title-line">
          <strong>{{ unit.title }}</strong>
        </div>
      </div>

      <div class="unit-actions">
        <el-button
          type="primary"
          text
          size="small"
          :icon="Plus"
          :disabled="busy"
          @click="emit('add-item', unit)"
        >
          添加内容
        </el-button>
        <el-button
          text
          size="small"
          :icon="FolderAdd"
          :disabled="busy"
          @click="emit('create-child', unit.id)"
        >
          新建子单元
        </el-button>
        <el-button text size="small" :icon="Edit" :disabled="busy" @click="emit('edit-unit', unit)">
          重命名
        </el-button>
        <el-button
          text
          size="small"
          type="danger"
          :icon="Delete"
          :disabled="busy"
          @click="emit('remove-unit', unit)"
        >
          删除
        </el-button>
      </div>
    </header>

    <div v-show="expanded" class="unit-body">
      <div v-if="unit.items.length" class="item-list">
        <OutlineItemRow
          v-for="item in unit.items"
          :key="item.id"
          :item="item"
          :busy="busy"
          :state-class="itemDnd.dropClass(item)"
          @open="emit('open-item', $event)"
          @results="emit('results-item', $event)"
          @remove="emit('remove-item', $event)"
          @drag-start="itemDnd.startDrag"
          @nudge="nudgeItem"
          @drag-end="itemDnd.endDrag"
          @enter="itemDnd.enter"
          @over="itemDnd.over"
          @leave="itemDnd.leave"
          @drop="itemDnd.drop"
        />
      </div>

      <div v-if="unit.children.length" class="child-unit-list">
        <CourseOutlineUnitNode
          v-for="child in unit.children"
          :key="child.id"
          :unit="child"
          :parent-id="unit.id"
          :depth="depth + 1"
          :busy="busy"
          @add-item="(target) => emit('add-item', target)"
          @create-child="(id) => emit('create-child', id)"
          @edit-unit="(target) => emit('edit-unit', target)"
          @remove-unit="(target) => emit('remove-unit', target)"
          @open-item="(item) => emit('open-item', item)"
          @results-item="(item) => emit('results-item', item)"
          @remove-item="(item) => emit('remove-item', item)"
          @reorder-unit="(id, target, after) => emit('reorder-unit', id, target, after)"
          @nudge-unit="nudgeChildUnit"
          @reorder-item="(unitId, id, target, after) => emit('reorder-item', unitId, id, target, after)"
        />
      </div>

      <div v-if="unit.items.length === 0 && unit.children.length === 0" class="unit-empty">内容为空</div>
    </div>
  </article>
</template>

<style scoped>
.unit-node {
  min-width: 0;
  background: var(--surface);
}

.unit-node--root + .unit-node--root {
  border-top: 1px solid var(--border);
}

.unit-node--nested {
  margin-left: 24px;
  border-left: 1px solid var(--border);
}

.unit-row {
  display: flex;
  min-height: 60px;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
}

.unit-node--root > .unit-row {
  background: var(--surface-muted);
}

.unit-node--nested > .unit-row {
  min-height: 52px;
  padding-left: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--surface);
}

.unit-toggle {
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: 6px;
  color: var(--text-secondary);
  background: transparent;
  cursor: pointer;
}

.unit-toggle:hover,
.unit-toggle:focus-visible {
  color: var(--text);
  background: var(--surface-muted);
  outline: none;
}

.unit-main,
.unit-title-line,
.unit-title-line strong {
  overflow: hidden;
  color: var(--text);
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.unit-actions {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
}

.drag-handle {
  display: inline-flex;
  width: 18px;
  height: 28px;
  flex: 0 0 18px;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  cursor: grab;
  opacity: 0.35;
  transition: opacity 0.15s;
}

.unit-row:hover .drag-handle {
  opacity: 1;
}

.drag-handle--disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.unit-row.drop-before {
  box-shadow: inset 0 2px 0 var(--brand);
}

.unit-row.drop-after {
  box-shadow: inset 0 -2px 0 var(--brand);
}

.unit-row.drop-into {
  box-shadow: inset 0 0 0 2px var(--brand);
}

.unit-body {
  min-width: 0;
}

.item-list {
  min-width: 0;
}

.child-unit-list {
  min-width: 0;
}

.unit-empty {
  display: flex;
  min-height: 44px;
  align-items: center;
  padding: 8px 14px 8px 40px;
  border-top: 1px solid var(--el-border-color-lighter);
  color: var(--text-muted);
  background: var(--surface-muted);
  font-size: 13px;
}
</style>
