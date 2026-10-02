<script setup lang="ts">
import { FolderOpened, Search } from '@element-plus/icons-vue'

import { MaterialViewState, type MaterialView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import type { LibraryCrumb } from '@/features/courses/useLibraryBrowser'
import { formatDateTime } from '@/shared/format'
import {
  libraryRowKey,
  libraryRowKindLabels,
  libraryRowTitle,
  libraryRowUpdatedAt,
  type LibraryRow,
} from '@/features/courses/library'
import { materialSizeLabel } from '@/features/courses/material'

const props = defineProps<{
  rows: LibraryRow[]
  crumbs: LibraryCrumb[]
  loading: boolean
  error: string
  selectable?: boolean
  folderSelectable?: boolean
  isChecked?: (row: LibraryRow) => boolean
  isDisabled?: (row: LibraryRow) => boolean
}>()

const emit = defineEmits<{
  'enter-folder': [material: MaterialView]
  'go-to-crumb': [index: number]
  retry: []
  toggle: [row: LibraryRow]
}>()

const keyword = defineModel<string>('keyword', { required: true })
const slots = defineSlots<{ actions?: (props: { row: LibraryRow }) => unknown }>()

function checked(row: LibraryRow): boolean {
  return props.isChecked?.(row) ?? false
}

function disabled(row: LibraryRow): boolean {
  return props.isDisabled?.(row) ?? false
}

function onRowClick(row: LibraryRow): void {
  if (!props.selectable || disabled(row)) return
  emit('toggle', row)
}
</script>

<template>
  <div class="library-table">
    <el-breadcrumb v-if="crumbs.length > 1" separator="/" class="library-crumbs">
      <el-breadcrumb-item v-for="(crumb, index) in crumbs" :key="`${crumb.id}-${index}`">
        <button type="button" class="crumb-link" @click="emit('go-to-crumb', index)">{{ crumb.name }}</button>
      </el-breadcrumb-item>
    </el-breadcrumb>

    <el-input v-model="keyword" clearable placeholder="搜索" :prefix-icon="Search" class="library-search" />

    <AsyncState
      :loading="loading"
      :error="error"
      :empty="rows.length === 0"
      :empty-text="keyword.trim() ? '无匹配内容' : crumbs.length > 1 ? '此文件夹为空' : '资料库为空'"
      @retry="emit('retry')"
    >
      <el-table
        :data="rows"
        :row-key="libraryRowKey"
        :row-class-name="selectable ? 'selectable-row' : ''"
        @row-click="onRowClick"
      >
        <el-table-column v-if="selectable" width="48">
          <template #default="{ row }: { row: LibraryRow }">
            <el-checkbox
              v-if="row.kind !== 'folder' || folderSelectable"
              :model-value="checked(row)"
              :disabled="disabled(row)"
              :aria-label="`选择 ${libraryRowTitle(row)}`"
              @click.stop
              @change="emit('toggle', row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="320">
          <template #default="{ row }: { row: LibraryRow }">
            <button
              v-if="row.kind === 'folder'"
              type="button"
              class="folder-link"
              @click.stop="emit('enter-folder', row.material)"
            >
              <el-icon><FolderOpened /></el-icon>{{ row.material.name }}
            </button>
            <span v-else-if="row.kind === 'file'" :class="{ muted: selectable && disabled(row) }">
              {{ row.material.name }}
              <el-tag
                v-if="row.material.state !== MaterialViewState.active"
                type="warning"
                size="small"
                effect="plain"
              >
                上传中
              </el-tag>
            </span>
            <span v-else-if="row.kind === 'question'" :class="{ muted: selectable && disabled(row) }">
              {{ row.question.title }}
            </span>
            <span v-else :class="{ muted: selectable && disabled(row) }">
              {{ row.problem.title }}
              <el-tag v-if="!row.problem.testcaseConfirmed" type="warning" size="small" effect="plain">
                未配置测试数据
              </el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }: { row: LibraryRow }">
            <el-tag effect="plain" size="small">{{ libraryRowKindLabels[row.kind] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }: { row: LibraryRow }">
            {{ row.kind === 'file' ? materialSizeLabel(row.material) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }: { row: LibraryRow }">
            {{ formatDateTime(libraryRowUpdatedAt(row)) }}
          </template>
        </el-table-column>
        <el-table-column v-if="slots.actions" label="操作" width="240" align="right">
          <template #default="{ row }: { row: LibraryRow }">
            <span @click.stop><slot name="actions" :row="row" /></span>
          </template>
        </el-table-column>
      </el-table>
    </AsyncState>
  </div>
</template>

<style scoped>
.library-crumbs {
  margin-bottom: 12px;
}

.library-search {
  max-width: 420px;
  margin-bottom: 14px;
}

.crumb-link,
.folder-link {
  padding: 0;
  border: 0;
  color: var(--el-color-primary);
  background: transparent;
  font: inherit;
  cursor: pointer;
}

.folder-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.muted {
  color: var(--el-text-color-placeholder);
}

:deep(.selectable-row) {
  cursor: pointer;
}
</style>
