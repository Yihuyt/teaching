<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { MagicStick, Plus } from '@element-plus/icons-vue'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { BuildView, GraphView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import KnowledgeGraphBuildWizard from '@/features/knowledgegraph/components/build/KnowledgeGraphBuildWizard.vue'
import { formatDateTime } from '@/shared/format'
import { createLatestRequestGuard } from '@/shared/latestRequest'

const props = defineProps<{ courseId: number }>()
const router = useRouter()

const loading = ref(true)
const loadError = ref('')
const graphs = ref<GraphView[]>([])
const dialogVisible = ref(false)
const editing = ref<GraphView>()
const formError = ref('')
const form = reactive({ name: '' })
const loadRequests = createLatestRequestGuard(() => props.courseId)

const wizardVisible = ref(false)
const wizardBuildId = ref<number>()
const builds = ref<BuildView[]>([])

const buildStatusLabels: Record<string, string> = {
  parsing: '解析中',
  toc_ready: '待确认目录',
  extracting: '抽取中',
  extracted: '待入库',
  failed: '失败',
}

async function loadBuilds(): Promise<void> {
  try {
    const response = await api.knowledgeGraphBuildList(props.courseId)
    builds.value = response.data
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

function openWizard(buildId?: number): void {
  wizardBuildId.value = buildId
  wizardVisible.value = true
}

async function removeBuild(build: BuildView): Promise<void> {
  if (!(await confirm(`确定删除构建记录「${build.materialName}」吗?`, '删除构建记录'))) return
  try {
    await api.knowledgeGraphBuildDelete(props.courseId, build.id)
    await loadBuilds()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

async function loadGraphs(): Promise<void> {
  const request = loadRequests.begin()
  loading.value = true
  loadError.value = ''
  graphs.value = []
  try {
    const response = await api.courseKnowledgeGraphList(request.snapshot, { management: true })
    if (!loadRequests.isCurrent(request)) return
    graphs.value = response.data
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) {
      loading.value = false
    }
  }
}

function openForm(graph?: GraphView): void {
  editing.value = graph
  Object.assign(form, graph ? { name: graph.name } : { name: '' })
  formError.value = ''
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!form.name.trim()) {
    formError.value = '请填写图谱名称'
    return
  }
  try {
    if (editing.value) {
      await api.courseKnowledgeGraphUpdate(props.courseId, editing.value.id, { name: form.name })
    } else {
      await api.courseKnowledgeGraphCreate(props.courseId, { name: form.name })
    }
    dialogVisible.value = false
    await loadGraphs()
    ElMessage.success('知识图谱已保存')
  } catch (error: unknown) {
    formError.value = errorMessage(error)
  }
}

const busyGraphId = ref<number>()

async function changePublication(graph: GraphView, publish: boolean): Promise<void> {
  if (!publish && !(await confirm(`确定取消发布“${graph.name}”吗？取消后学生不再看到该图谱。`, '取消发布')))
    return
  busyGraphId.value = graph.id
  try {
    if (publish) {
      await api.courseKnowledgeGraphPublish(props.courseId, graph.id)
    } else {
      await api.courseKnowledgeGraphUnpublish(props.courseId, graph.id)
    }
    await loadGraphs()
    ElMessage.success(publish ? '知识图谱已发布' : '知识图谱已取消发布')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    busyGraphId.value = undefined
  }
}

async function remove(graph: GraphView): Promise<void> {
  if (!(await confirm(`确定删除知识图谱“${graph.name}”吗？`, '删除知识图谱'))) return
  try {
    await api.courseKnowledgeGraphDelete(props.courseId, graph.id)
    await loadGraphs()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

function openEditor(graph: GraphView): void {
  void router.push(`/focus/admin/courses/${props.courseId}/knowledge-graphs/${graph.id}/edit`)
}

function onWizardCompleted(): void {
  void loadGraphs()
  void loadBuilds()
}

watch(
  () => props.courseId,
  () => {
    wizardVisible.value = false
    void loadGraphs()
    void loadBuilds()
  },
  { immediate: true },
)
</script>

<template>
  <section>
    <div class="manager-toolbar">
      <el-button :icon="MagicStick" @click="openWizard()">从教材生成</el-button>
      <el-button type="primary" :icon="Plus" @click="openForm()">创建图谱</el-button>
    </div>

    <el-alert v-if="builds.length" type="info" :closable="false" class="builds-alert">
      <template #title>
        有 {{ builds.length }} 个未完成的教材构建:
        <span v-for="build in builds" :key="build.id" class="build-item">
          <el-button link type="primary" @click="openWizard(build.id)">
            {{ build.materialName }}({{ buildStatusLabels[build.status] }})
          </el-button>
          <el-button link type="danger" @click="removeBuild(build)">删除</el-button>
        </span>
      </template>
    </el-alert>

    <AsyncState
      :loading="loading"
      :error="loadError"
      :empty="graphs.length === 0"
      empty-text="本课程暂无知识图谱"
      @retry="loadGraphs"
    >
      <el-table :data="graphs" @row-dblclick="openEditor">
        <el-table-column prop="name" label="图谱名称" min-width="220" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }: { row: GraphView }">
            <el-tag :type="row.published ? 'success' : 'info'" effect="plain">
              {{ row.published ? '已发布' : '未发布' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="190">
          <template #default="{ row }: { row: GraphView }">{{ formatDateTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" align="right">
          <template #default="{ row }: { row: GraphView }">
            <el-button link type="primary" @click="openEditor(row)">打开图谱</el-button>
            <el-button link :loading="busyGraphId === row.id" @click="changePublication(row, !row.published)">
              {{ row.published ? '取消发布' : '发布' }}
            </el-button>
            <el-button link @click="openForm(row)">重命名</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </AsyncState>

    <el-dialog v-model="dialogVisible" :title="editing ? '重命名图谱' : '创建知识图谱'" width="480px">
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="dialog-alert" />
      <el-form :model="form" label-position="top">
        <el-form-item label="图谱名称" required
          ><el-input v-model="form.name" maxlength="128"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <KnowledgeGraphBuildWizard
      v-model="wizardVisible"
      :course-id="courseId"
      :build-id="wizardBuildId"
      @completed="onWizardCompleted"
      @changed="loadBuilds"
    />
  </section>
</template>

<style scoped>
.manager-toolbar {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 14px;
}

.builds-alert {
  margin-bottom: 14px;
}

.build-item {
  margin-left: 8px;
}

.dialog-alert {
  margin-bottom: 16px;
}
</style>
