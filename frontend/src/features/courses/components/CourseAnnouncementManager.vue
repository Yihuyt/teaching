<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { AnnouncementView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import { formatDateTime } from '@/shared/format'
import { createLatestRequestGuard } from '@/shared/latestRequest'

const props = defineProps<{ courseId: number }>()

const loading = ref(true)
const loadError = ref('')
const announcements = ref<AnnouncementView[]>([])
const page = ref(1)
const pageSize = 20
const total = ref(0)
const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<AnnouncementView>()
const formError = ref('')
const form = reactive({ title: '', contentMarkdown: '' })
const loadRequests = createLatestRequestGuard(
  () => ({ courseId: props.courseId, page: page.value }),
  (left, right) => left.courseId === right.courseId && left.page === right.page,
)

async function load(): Promise<void> {
  const request = loadRequests.begin()
  loading.value = true
  loadError.value = ''
  announcements.value = []
  try {
    const response = await api.announcementList({
      courseId: request.snapshot.courseId,
      page: request.snapshot.page,
      size: pageSize,
    })
    if (!loadRequests.isCurrent(request)) return
    announcements.value = response.data.items
    total.value = response.data.total
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) {
      loading.value = false
    }
  }
}

function openForm(announcement?: AnnouncementView): void {
  editing.value = announcement
  formError.value = ''
  Object.assign(
    form,
    announcement
      ? { title: announcement.title, contentMarkdown: announcement.contentMarkdown }
      : { title: '', contentMarkdown: '' },
  )
  dialogVisible.value = true
}

async function save(): Promise<void> {
  const title = form.title.trim()
  if (!title) {
    formError.value = '请输入公告标题'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    if (editing.value) {
      await api.announcementUpdate(editing.value.id, {
        title,
        contentMarkdown: form.contentMarkdown,
      })
    } else {
      await api.announcementCreate({
        courseId: props.courseId,
        title,
        contentMarkdown: form.contentMarkdown,
      })
    }
    dialogVisible.value = false
    await load()
    ElMessage.success('课程公告已保存')
  } catch (error: unknown) {
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function remove(announcement: AnnouncementView): Promise<void> {
  if (!(await confirm(`确定删除课程公告“${announcement.title}”吗？`, '删除课程公告'))) return
  try {
    await api.announcementDelete(announcement.id)
    if (announcements.value.length === 1 && page.value > 1) page.value -= 1
    await load()
    ElMessage.success('课程公告已删除')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

watch(
  () => props.courseId,
  () => {
    page.value = 1
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="drawer-toolbar">
    <span>共 {{ total }} 条公告</span>
    <el-button type="primary" size="small" @click="openForm()">创建公告</el-button>
  </div>
  <AsyncState
    :loading="loading"
    :error="loadError"
    :empty="announcements.length === 0"
    empty-text="暂无课程公告"
    @retry="load"
  >
    <el-table :data="announcements">
      <el-table-column prop="title" label="标题" min-width="300" />
      <el-table-column label="发布时间" width="190">
        <template #default="{ row }: { row: AnnouncementView }">
          {{ formatDateTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" align="right">
        <template #default="{ row }: { row: AnnouncementView }">
          <el-button link @click="openForm(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      :page-size="pageSize"
      :total="total"
      layout="total, prev, pager, next"
      class="pagination"
      @current-change="load"
    />
  </AsyncState>

  <el-dialog
    v-model="dialogVisible"
    :title="editing ? '编辑课程公告' : '创建课程公告'"
    width="700px"
    append-to-body
  >
    <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="dialog-alert" />
    <el-form :model="form" label-position="top">
      <el-form-item label="公告标题" required>
        <el-input v-model="form.title" maxlength="255" />
      </el-form-item>
      <el-form-item label="公告内容">
        <el-input v-model="form.contentMarkdown" type="textarea" :rows="9" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.drawer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.dialog-alert {
  margin-bottom: 16px;
}

.pagination {
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
