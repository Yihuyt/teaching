<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, ArrowUp, Back, Delete, Plus, Right } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'

import { shiftTocPages, type TocRow } from '@/features/knowledgegraph/tocShift'

export type { TocRow }

const props = defineProps<{
  courseId: number
  buildId: number
  degraded: boolean
  notes: string[]
  pageCount: number
}>()

const rows = defineModel<TocRow[]>({ required: true })

const selectedIndex = ref<number>()
const shiftDelta = ref(0)

function shiftAll(): void {
  if (!shiftDelta.value) return
  rows.value = shiftTocPages(rows.value, shiftDelta.value, props.pageCount)
  shiftDelta.value = 0
}
const previewText = ref('')
const previewPage = ref<number>()
const previewLoading = ref(false)

async function selectRow(index: number): Promise<void> {
  selectedIndex.value = index
  const page = rows.value[index]?.page
  if (!page || page < 1 || page > props.pageCount) {
    previewText.value = ''
    previewPage.value = undefined
    return
  }
  previewLoading.value = true
  previewPage.value = page
  try {
    const response = await api.knowledgeGraphBuildPagePreview(props.courseId, props.buildId, page)
    previewText.value = response.data.text
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    previewLoading.value = false
  }
}

/** 止页不能早于起页:起页改动时把止页顶上去(输入框的 min 只挡新输入,不会回收已有值) */
function keepEndPageAfterStart(row: TocRow): void {
  if (row.endPage < row.page) row.endPage = row.page
}

function move(index: number, delta: number): void {
  const target = index + delta
  if (target < 0 || target >= rows.value.length) return
  const next = [...rows.value]
  const moved = next[index]
  const displaced = next[target]
  if (!moved || !displaced) return
  next[index] = displaced
  next[target] = moved
  rows.value = next
  selectedIndex.value = target
}

function indent(index: number, delta: number): void {
  const row = rows.value[index]
  if (!row) return
  const level = row.level + delta
  if (level < 1 || level > 6) return
  rows.value = rows.value.map((item, i) => (i === index ? { ...item, level } : item))
}

function insertBelow(index: number): void {
  const base = rows.value[index]
  const next = [...rows.value]
  next.splice(index + 1, 0, {
    number: '',
    title: '',
    level: base?.level ?? 1,
    page: base?.page ?? 1,
    endPage: base?.endPage ?? props.pageCount,
  })
  rows.value = next
  selectedIndex.value = index + 1
}

function remove(index: number): void {
  rows.value = rows.value.filter((_, i) => i !== index)
  if (selectedIndex.value === index) selectedIndex.value = undefined
}

watch(
  () => props.buildId,
  () => {
    selectedIndex.value = undefined
    previewText.value = ''
    previewPage.value = undefined
  },
)
</script>

<template>
  <section class="toc-editor">
    <el-alert
      v-if="degraded"
      type="warning"
      title="目录识别不完整，请逐条核对"
      :closable="false"
      class="editor-alert"
    />
    <p v-for="note in notes" :key="note" class="note">{{ note }}</p>
    <div class="shift-row">
      <el-input-number
        v-model="shiftDelta"
        :min="-pageCount"
        :max="pageCount"
        :controls="false"
        class="shift-input"
      />
      <el-button :disabled="!shiftDelta" @click="shiftAll">页码整体平移</el-button>
    </div>

    <div class="editor-body">
      <div class="entry-list">
        <div class="entry-header">
          <span class="col-number">编号</span>
          <span class="col-title">标题(缩进表示层级)</span>
          <span class="col-page">起页(PDF)</span>
          <span class="col-page">止页(PDF)</span>
          <span class="col-actions">操作</span>
        </div>
        <div
          v-for="(row, index) in rows"
          :key="index"
          class="entry-row"
          :class="{ selected: selectedIndex === index }"
          @click="selectRow(index)"
        >
          <el-input v-model="row.number" class="col-number" size="small" placeholder="如 3.2" />
          <div class="col-title" :style="{ paddingLeft: `${(row.level - 1) * 22}px` }">
            <el-input v-model="row.title" size="small" placeholder="标题" />
          </div>
          <el-input-number
            v-model="row.page"
            class="col-page"
            size="small"
            :min="1"
            :max="pageCount"
            :controls="false"
            @change="keepEndPageAfterStart(row)"
          />
          <el-input-number
            v-model="row.endPage"
            class="col-page"
            size="small"
            :min="row.page || 1"
            :max="pageCount"
            :controls="false"
          />
          <span class="col-actions" @click.stop>
            <el-button link size="small" :icon="Back" title="升级" @click="indent(index, -1)" />
            <el-button link size="small" :icon="Right" title="降级" @click="indent(index, 1)" />
            <el-button link size="small" :icon="ArrowUp" title="上移" @click="move(index, -1)" />
            <el-button link size="small" :icon="ArrowDown" title="下移" @click="move(index, 1)" />
            <el-button link size="small" :icon="Plus" title="在下方插入" @click="insertBelow(index)" />
            <el-button link size="small" type="danger" :icon="Delete" title="删除" @click="remove(index)" />
          </span>
        </div>
        <el-button v-if="rows.length === 0" plain @click="insertBelow(-1)">添加第一条目录</el-button>
      </div>

      <aside class="page-preview">
        <h4 v-if="previewPage">第 {{ previewPage }} 页正文开头(核对边界)</h4>
        <h4 v-else>点击左侧条目查看该页正文</h4>
        <pre v-loading="previewLoading" class="preview-text">{{ previewText }}</pre>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.editor-alert {
  margin-bottom: 12px;
}

.note {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.shift-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.shift-input {
  width: 90px;
}

.editor-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
}

.entry-list {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  max-height: 520px;
  overflow-y: auto;
}

.entry-header,
.entry-row {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) 90px 90px 210px;
  gap: 8px;
  align-items: center;
  padding: 6px 10px;
}

.entry-header {
  position: sticky;
  top: 0;
  background: var(--el-fill-color-light);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  z-index: 1;
}

.entry-row {
  border-top: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}

.entry-row.selected {
  background: var(--el-color-primary-light-9);
}

.col-actions {
  display: flex;
  align-items: center;
}

.page-preview {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px;
}

.page-preview h4 {
  margin: 0 0 8px;
  font-size: 13px;
}

.preview-text {
  margin: 0;
  max-height: 460px;
  overflow-y: auto;
  white-space: pre-wrap;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
</style>
