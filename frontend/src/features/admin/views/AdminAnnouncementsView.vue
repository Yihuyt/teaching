<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { AnnouncementView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import { formatDateTime } from '@/shared/format'

const loading = ref(true)
const loadError = ref('')
const items = ref<AnnouncementView[]>([])
const total = ref(0)
const page = ref(1)
const dialogVisible = ref(false)
const editing = ref<AnnouncementView>()
const formError = ref('')
const saving = ref(false)
const form = reactive({
  title: '',
  contentMarkdown: '',
})

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.announcementList({ page: page.value, size: 20 })
    items.value = response.data.items
    total.value = response.data.total
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function openForm(announcement?: AnnouncementView): void {
  editing.value = announcement
  Object.assign(
    form,
    announcement
      ? { title: announcement.title, contentMarkdown: announcement.contentMarkdown }
      : { title: '', contentMarkdown: '' },
  )
  formError.value = ''
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!form.title.trim()) {
    formError.value = '请输入公告标题'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    if (editing.value) {
      await api.announcementUpdate(editing.value.id, {
        title: form.title,
        contentMarkdown: form.contentMarkdown,
      })
    } else {
      await api.announcementCreate({
        courseId: null,
        title: form.title,
        contentMarkdown: form.contentMarkdown,
      })
    }
    dialogVisible.value = false
    await load()
    ElMessage.success('公告已保存')
  } catch (error: unknown) {
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function remove(announcement: AnnouncementView): Promise<void> {
  if (!(await confirm(`确定删除公告“${announcement.title}”吗？`, '删除公告'))) return
  try {
    await api.announcementDelete(announcement.id)
    await load()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="公告管理">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openForm()">创建公告</el-button>
      </template>
    </PageHeader>
    <section class="panel panel-body">
      <AsyncState
        :loading="loading"
        :error="loadError"
        :empty="items.length === 0"
        empty-text="暂无公告"
        @retry="load"
      >
        <el-table :data="items">
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
          :page-size="20"
          :total="total"
          layout="total, prev, pager, next"
          class="pagination"
          @current-change="load"
        />
      </AsyncState>
    </section>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑公告' : '创建公告'" width="720px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="dialog-alert" />
      <el-form :model="form" label-position="top">
        <el-form-item label="公告标题" required
          ><el-input v-model="form.title" maxlength="255"
        /></el-form-item>
        <el-form-item label="公告内容">
          <el-input v-model="form.contentMarkdown" type="textarea" :rows="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pagination {
  justify-content: flex-end;
  margin-top: 20px;
}

.dialog-alert {
  margin-bottom: 16px;
}
</style>
