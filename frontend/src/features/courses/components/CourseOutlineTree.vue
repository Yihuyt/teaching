<script setup lang="ts">
import { computed } from 'vue'

import type { CourseOutlineUnitView, CourseOutlineItemView, CourseOutlineView } from '@/api/generated'
import { sortOutline } from '@/features/courses/courseOutline'
import { courseOutlineItemOpenLabels, courseOutlineItemTypeLabels } from '@/shared/labels'

defineOptions({ name: 'CourseOutlineTree' })

const props = defineProps<{
  outline?: CourseOutlineView | undefined
  units?: CourseOutlineUnitView[] | undefined
}>()

const emit = defineEmits<{ open: [item: CourseOutlineItemView] }>()

const isRoot = computed(() => props.units === undefined)
const sorted = computed(() => (props.outline ? sortOutline(props.outline) : undefined))
const topItems = computed<CourseOutlineItemView[]>(() => sorted.value?.items ?? [])
const rows = computed<CourseOutlineUnitView[]>(() => {
  if (props.units !== undefined) return props.units
  return sorted.value?.units ?? []
})
</script>

<template>
  <div class="outline-tree" :class="{ 'outline-tree--root': isRoot }">
    <p v-if="isRoot && rows.length === 0 && topItems.length === 0" class="outline-empty">内容为空</p>
    <section v-if="isRoot && topItems.length" class="outline-unit">
      <ul class="outline-items outline-items--top">
        <li v-for="item in topItems" :key="item.id" class="outline-item">
          <div class="outline-item__title">
            <span class="outline-item__type">{{ courseOutlineItemTypeLabels[item.itemType] }}</span>
            <span class="outline-item__name">{{ item.title }}</span>
          </div>
          <el-button link type="primary" @click="emit('open', item)">
            {{ courseOutlineItemOpenLabels[item.itemType] }}
          </el-button>
        </li>
      </ul>
    </section>
    <section
      v-for="unit in rows"
      :key="unit.id"
      class="outline-unit"
      :class="{ 'outline-unit--nested': !isRoot }"
    >
      <header class="outline-unit__title">
        <strong>{{ unit.title }}</strong>
      </header>
      <ul v-if="unit.items.length" class="outline-items">
        <li v-for="item in unit.items" :key="item.id" class="outline-item">
          <div class="outline-item__title">
            <span class="outline-item__type">{{ courseOutlineItemTypeLabels[item.itemType] }}</span>
            <span class="outline-item__name">{{ item.title }}</span>
          </div>
          <el-button link type="primary" @click="emit('open', item)">
            {{ courseOutlineItemOpenLabels[item.itemType] }}
          </el-button>
        </li>
      </ul>
      <CourseOutlineTree v-if="unit.children.length" :units="unit.children" @open="emit('open', $event)" />
    </section>
  </div>
</template>

<style scoped>
.outline-tree--root {
  display: grid;
  gap: 12px;
}

.outline-unit {
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: var(--surface);
}

.outline-unit--nested {
  margin: 0 16px 16px;
}

.outline-unit__title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 46px;
  padding: 0 16px;
}

.outline-items {
  margin: 0;
  padding: 0;
  list-style: none;
  border-top: 1px solid var(--border);
}

.outline-items--top {
  border-top: 0;
}

.outline-items--top .outline-item {
  padding-left: 16px;
}

.outline-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  min-height: 46px;
  padding: 6px 16px 6px 36px;
  border-bottom: 1px solid var(--border);
}

.outline-item:last-child {
  border-bottom: 0;
}

.outline-item__title {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 12px;
}

.outline-item__type {
  flex-shrink: 0;
  padding: 1px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  color: var(--text-secondary);
  font-size: 12px;
}

.outline-item__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.outline-empty {
  margin: 0;
  padding: 44px 0;
  color: var(--text-muted);
  text-align: center;
}
</style>
