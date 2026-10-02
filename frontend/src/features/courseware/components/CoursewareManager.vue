<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { CoursewareSummary, LearningReport } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import { formatEpochMillis } from '@/shared/format'
import { createLatestRequestGuard } from '@/shared/latestRequest'

const props = defineProps<{ courseId: number }>()
const router = useRouter()

const loading = ref(true)
const loadError = ref('')
const items = ref<CoursewareSummary[]>([])
const loadRequests = createLatestRequestGuard(() => props.courseId)

async function load(): Promise<void> {
  const request = loadRequests.begin()
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.coursewareList(request.snapshot)
    if (!loadRequests.isCurrent(request)) return
    items.value = response.data
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) {
      loading.value = false
    }
  }
}

watch(() => props.courseId, load, { immediate: true })

function openWorkbench(id: number): void {
  void router.push(`/focus/admin/courses/${props.courseId}/coursewares/${id}/edit`)
}

const createVisible = ref(false)
const creating = ref(false)
const createForm = ref({ title: '' })

function openCreate(): void {
  createForm.value = { title: '' }
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  const title = createForm.value.title.trim()
  if (!title) {
    ElMessage.warning('请填写课件标题')
    return
  }
  creating.value = true
  try {
    const response = await api.coursewareCreate(props.courseId, { title })
    createVisible.value = false
    openWorkbench(response.data.id)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    creating.value = false
  }
}

const publishingId = ref<number | null>(null)

async function togglePublish(item: CoursewareSummary): Promise<void> {
  publishingId.value = item.id
  try {
    const response = item.published
      ? await api.coursewareUnpublish(props.courseId, item.id)
      : await api.coursewarePublish(props.courseId, item.id)
    const index = items.value.findIndex((row) => row.id === item.id)
    if (index >= 0) items.value[index] = response.data
    ElMessage.success(response.data.published ? '已发布,学生可以学习了' : '已取消发布')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    publishingId.value = null
  }
}

function openPreview(item: CoursewareSummary): void {
  void router.push(`/focus/admin/courses/${props.courseId}/coursewares/${item.id}/preview`)
}

async function remove(item: CoursewareSummary): Promise<void> {
  if (!(await confirm(`确定删除课件“${item.title}”吗?配图、讲稿音频、素材与学生作答记录将一并删除。`, '删除课件'))) return
  try {
    await api.coursewareDelete(props.courseId, item.id)
    ElMessage.success('已删除')
    await load()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

const reportVisible = ref(false)
const reportLoading = ref(false)
const reportError = ref('')
const reportTitle = ref('')
const report = ref<LearningReport>()

async function openReport(item: CoursewareSummary): Promise<void> {
  reportVisible.value = true
  reportLoading.value = true
  reportError.value = ''
  reportTitle.value = item.title
  report.value = undefined
  try {
    const response = await api.coursewareLearningReport(props.courseId, item.id)
    report.value = response.data
  } catch (error: unknown) {
    reportError.value = errorMessage(error)
  } finally {
    reportLoading.value = false
  }
}
</script>

<template>
  <section>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新建课件</el-button>
    </div>

    <AsyncState :loading="loading" :error="loadError" :empty="items.length === 0" @retry="load">
      <template #empty>
        <p class="empty-text">暂无课件</p>
      </template>
      <el-table :data="items">
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="sceneCount" label="页数" width="70" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }: { row: CoursewareSummary }">
            <el-tag size="small" :type="row.published ? 'success' : 'info'">
              {{ row.published ? '已发布' : '未发布' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }: { row: CoursewareSummary }">
            {{ formatEpochMillis(row.updatedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="380" fixed="right">
          <template #default="{ row }: { row: CoursewareSummary }">
            <el-button link type="primary" @click="openWorkbench(row.id)">工作台</el-button>
            <el-button link :disabled="row.sceneCount === 0" @click="openPreview(row)">预览</el-button>
            <el-button
              link
              :loading="publishingId === row.id"
              :disabled="!row.published && row.sceneCount === 0"
              @click="togglePublish(row)"
            >
              {{ row.published ? '取消发布' : '发布' }}
            </el-button>
            <el-button link @click="openReport(row)">学习报告</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </AsyncState>

    <el-dialog v-model="createVisible" title="新建课件" width="440px">
      <el-form label-width="60px">
        <el-form-item label="标题">
          <el-input v-model="createForm.title" maxlength="255" placeholder="如:牛顿第二定律" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reportVisible" :title="`学习报告 —— ${reportTitle}`" width="760px">
      <AsyncState
        :loading="reportLoading"
        :error="reportError"
        :empty="!report || report.students.length === 0"
      >
        <template #empty>
          <p class="empty-text">还没有学生学习记录</p>
        </template>
        <template v-if="report">
          <h3 class="report-heading">按学生</h3>
          <el-table :data="report.students" size="small">
            <el-table-column prop="displayName" label="学生" min-width="140" />
            <el-table-column label="浏览页数" width="110">
              <template #default="{ row }">{{ row.scenesViewed }} / {{ report.sceneCount }}</template>
            </el-table-column>
            <el-table-column label="答题(对/总)" width="120">
              <template #default="{ row }">{{ row.quizCorrect }} / {{ row.quizAttempts }}</template>
            </el-table-column>
            <el-table-column label="最近学习" min-width="160">
              <template #default="{ row }">{{ formatEpochMillis(row.lastActiveAt) }}</template>
            </el-table-column>
          </el-table>

          <template v-if="report.questions.length">
            <h3 class="report-heading">按题目</h3>
            <el-table :data="report.questions" size="small">
              <el-table-column prop="blockId" label="题目" min-width="160" />
              <el-table-column prop="attempts" label="作答次数" width="110" />
              <el-table-column label="正确率" width="110">
                <template #default="{ row }">
                  {{ row.attempts > 0 ? Math.round((row.correctCount / row.attempts) * 100) : 0 }}%
                </template>
              </el-table-column>
            </el-table>
          </template>

          <template v-if="report.qaRecords.length">
            <h3 class="report-heading">课堂提问(最近 {{ report.qaRecords.length }} 条)</h3>
            <el-table :data="report.qaRecords" size="small">
              <el-table-column prop="displayName" label="学生" width="120" />
              <el-table-column prop="question" label="提问" min-width="200" show-overflow-tooltip />
              <el-table-column prop="answer" label="AI 回答" min-width="240" show-overflow-tooltip />
              <el-table-column label="时间" width="160">
                <template #default="{ row }">{{ formatEpochMillis(row.askedAt) }}</template>
              </el-table-column>
            </el-table>
          </template>
        </template>
      </AsyncState>
    </el-dialog>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.empty-text {
  margin: 0;
  padding: 32px 0;
  color: var(--text-muted);
  text-align: center;
}

.report-heading {
  margin: 12px 0 8px;
  font-size: 14px;
}
</style>
