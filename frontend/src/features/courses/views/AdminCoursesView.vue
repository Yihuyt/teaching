<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick, MoreFilled, Plus, Search } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

import { api, errorMessage } from '@/api/client'
import { confirm } from '@/shared/dialogs'
import type { CourseView } from '@/api/generated'
import AiServiceSettings from '@/features/account/components/AiServiceSettings.vue'
import AsyncState from '@/shared/components/AsyncState.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import { formatDateTime } from '@/shared/format'

const loading = ref(true)
const router = useRouter()
const saving = ref(false)
const loadError = ref('')
const formError = ref('')
const items = ref<CourseView[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, keyword: '', management: true })
const dialogVisible = ref(false)
const aiDialogVisible = ref(false)
const editing = ref<CourseView>()
const form = reactive({
  title: '',
  descriptionMarkdown: '',
})

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.courseList(query)
    items.value = response.data.items
    total.value = response.data.total
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function search(): void {
  query.page = 1
  void load()
}

function openCreate(): void {
  editing.value = undefined
  Object.assign(form, {
    title: '',
    descriptionMarkdown: '',
  })
  formError.value = ''
  dialogVisible.value = true
}

function openEdit(course: CourseView): void {
  editing.value = course
  Object.assign(form, {
    title: course.title,
    descriptionMarkdown: course.descriptionMarkdown,
  })
  formError.value = ''
  dialogVisible.value = true
}

function openManagement(course: CourseView): void {
  void router.push({ name: 'admin-course-detail', params: { courseId: course.id } })
}

async function save(): Promise<void> {
  if (!form.title.trim()) {
    formError.value = '请输入课程名称'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    if (editing.value) {
      await api.courseUpdate(editing.value.id, {
        title: form.title,
        descriptionMarkdown: form.descriptionMarkdown,
      })
      ElMessage.success('课程已更新')
    } else {
      await api.courseCreate({
        title: form.title,
        descriptionMarkdown: form.descriptionMarkdown,
      })
      ElMessage.success('课程已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error: unknown) {
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function remove(course: CourseView): Promise<void> {
  if (
    !(await confirm(
      `确定删除课程“${course.title}”吗？课程内容、资料、公告与学生数据将一并删除。`,
      '删除课程',
    ))
  ) {
    return
  }
  try {
    await api.courseDelete(course.id)
    ElMessage.success('课程已删除')
    await load()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

async function togglePublication(course: CourseView): Promise<void> {
  const action = course.published ? '取消发布' : '发布'
  if (!(await confirm(`确定${action}课程“${course.title}”吗？`, `${action}课程`))) {
    return
  }
  try {
    if (course.published) await api.courseUnpublish(course.id)
    else await api.coursePublish(course.id)
    ElMessage.success(`课程已${action}`)
    await load()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="课程管理">
      <template #actions>
        <el-button :icon="MagicStick" @click="aiDialogVisible = true">AI 服务配置</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">创建课程</el-button>
      </template>
    </PageHeader>
    <section class="panel panel-body">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="搜索课程名称"
          style="width: 360px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #append><el-button :icon="Search" @click="search" /></template>
        </el-input>
        <span class="muted">共 {{ total }} 门课程</span>
      </div>
      <AsyncState
        :loading="loading"
        :error="loadError"
        :empty="items.length === 0"
        empty-text="暂无课程"
        @retry="load"
      >
        <el-table :data="items">
          <el-table-column prop="title" label="课程名称" min-width="280" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }: { row: CourseView }">
              <el-tag :type="row.published ? 'success' : 'info'" effect="plain" size="small">
                {{ row.published ? '已发布' : '未发布' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="190">
            <template #default="{ row }: { row: CourseView }">
              {{ formatDateTime(row.updatedAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="310" align="right">
            <template #default="{ row }: { row: CourseView }">
              <el-button link type="primary" @click="openManagement(row)">管理课程</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="!row.published" link type="success" @click="togglePublication(row)">
                发布
              </el-button>
              <el-button v-else link @click="togglePublication(row)">取消发布</el-button>
              <el-dropdown trigger="click">
                <el-button link :icon="MoreFilled" aria-label="更多操作" />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item @click="remove(row)">删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          layout="total, sizes, prev, pager, next"
          class="pagination"
          @current-change="load"
          @size-change="search"
        />
      </AsyncState>
    </section>

    <el-dialog v-model="aiDialogVisible" title="AI 服务配置" width="720px">
      <AiServiceSettings />
    </el-dialog>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑课程' : '创建课程'" width="680px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="dialog-alert" />
      <el-form :model="form" label-position="top">
        <el-form-item label="课程名称" required>
          <el-input v-model="form.title" maxlength="128" />
        </el-form-item>
        <el-form-item label="课程说明">
          <el-input v-model="form.descriptionMarkdown" type="textarea" :rows="8" />
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

:deep(.el-dialog .el-select) {
  width: 100%;
}

</style>
