<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { ProjectView } from '@/api/generated'

const props = defineProps<{ courseId: number; management?: boolean }>()
const router = useRouter()

function studioRoute(projectId: number): RouteLocationRaw {
  return {
    name: props.management ? 'admin-blockcoding-studio' : 'blockcoding-studio',
    params: { courseId: props.courseId, projectId },
  }
}

const projects = ref<ProjectView[]>([])
const loading = ref(false)
const page = ref(1)
const size = 20
const total = ref(0)

const createDialogVisible = ref(false)
const newProjectName = ref('')
const creating = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    const response = await api.blockCodingProjectList(props.courseId, { page: page.value, size })
    projects.value = response.data.items
    total.value = response.data.total
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function create(): Promise<void> {
  const name = newProjectName.value.trim()
  if (!name) {
    ElMessage.warning('请给作品起个名字')
    return
  }
  creating.value = true
  try {
    const response = await api.blockCodingProjectCreate(props.courseId, { name })
    createDialogVisible.value = false
    newProjectName.value = ''
    await router.push(studioRoute(response.data.id))
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    creating.value = false
  }
}

async function rename(project: ProjectView): Promise<void> {
  let name: string
  try {
    const result = await ElMessageBox.prompt('新的作品名称', '重命名', {
      inputValue: project.name,
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPattern: /\S/,
      inputErrorMessage: '名称不能为空',
    })
    name = result.value.trim()
  } catch {
    return
  }
  try {
    await api.blockCodingProjectRename(props.courseId, project.id, { name })
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

async function remove(project: ProjectView): Promise<void> {
  if (!(await confirm(`删除作品「${project.name}」？作品文件和全部 AI 对话都会一并删除。`, '删除作品')))
    return
  try {
    await api.blockCodingProjectDelete(props.courseId, project.id)
    await load()
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

function open(project: ProjectView): void {
  void router.push(studioRoute(project.id))
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="blockcoding-projects">
    <div class="blockcoding-projects__toolbar">
      <el-button type="primary" @click="createDialogVisible = true">新建作品</el-button>
    </div>

    <el-skeleton v-if="loading && projects.length === 0" :rows="4" animated />

    <p v-else-if="projects.length === 0" class="blockcoding-projects__empty">还没有作品</p>

    <div v-else class="blockcoding-projects__grid">
      <el-card
        v-for="project in projects"
        :key="project.id"
        class="blockcoding-projects__card"
        shadow="hover"
      >
        <div class="blockcoding-projects__card-body" @click="open(project)">
          <strong>{{ project.name }}</strong>
          <span class="blockcoding-projects__time">
            {{ project.hasFile ? `保存于 ${formatTime(project.fileUpdatedAt!)}` : '尚未保存内容' }}
          </span>
        </div>
        <div class="blockcoding-projects__actions">
          <el-button size="small" @click.stop="open(project)">打开</el-button>
          <el-button size="small" text @click.stop="rename(project)">重命名</el-button>
          <el-button size="small" text type="danger" @click.stop="remove(project)"> 删除 </el-button>
        </div>
      </el-card>
    </div>

    <el-pagination
      v-if="total > size"
      v-model:current-page="page"
      class="blockcoding-projects__pagination"
      layout="prev, pager, next"
      :total="total"
      :page-size="size"
      @current-change="load"
    />

    <el-dialog v-model="createDialogVisible" title="新建作品" width="400px">
      <el-input
        v-model="newProjectName"
        maxlength="100"
        placeholder="作品名称，如：接苹果小游戏"
        @keyup.enter="create"
      />
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建并打开</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.blockcoding-projects__empty {
  margin: 16px 0 0;
  color: var(--text-muted);
}

.blockcoding-projects__toolbar {
  display: flex;
  justify-content: flex-end;
}

.blockcoding-projects__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  margin-top: 16px;
}

.blockcoding-projects__card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  cursor: pointer;
}

.blockcoding-projects__time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.blockcoding-projects__actions {
  display: flex;
  gap: 4px;
  margin-top: 12px;
}

.blockcoding-projects__pagination {
  margin-top: 16px;
  justify-content: center;
}
</style>
