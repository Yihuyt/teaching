<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

import { api, errorMessage } from '@/api/client'
import {
  SubmissionViewStatus,
  type CaseResultView,
  type SubmissionDetailView,
  type SubmissionView,
} from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import CodeEditor from '@/features/programming/components/CodeEditor.vue'
import { confirm } from '@/shared/dialogs'
import { formatDateTime } from '@/shared/format'
import { programmingLanguageLabels, submissionStatusLabels, submissionStatusTagTypes } from '@/shared/labels'
import { useSessionStore } from '@/stores/session'

const route = useRoute()
const session = useSessionStore()
const rejudging = ref(false)
const courseId = computed(() => Number(route.params.courseId))
const problemId = computed(() => Number(route.params.problemId))
const submissionId = computed(() => Number(route.params.submissionId))
const loading = ref(true)
const loadError = ref('')
const detail = ref<SubmissionDetailView>()
const submission = computed(() => detail.value?.submission)
/** 轮询:前 30 秒每 2 秒,之后每 5 秒,5 分钟后停下交给服务端对账(10 分钟超时自动重投) */
const POLL_FAST_MS = 2_000
const POLL_SLOW_MS = 5_000
const POLL_FAST_WINDOW_MS = 30_000
const POLL_LIMIT_MS = 5 * 60_000
const pollingExhausted = ref(false)
let pollingStartedAt: number | null = null
let refreshTimer: number | undefined

function stopPolling(): void {
  if (refreshTimer !== undefined) {
    window.clearTimeout(refreshTimer)
    refreshTimer = undefined
  }
}

function isPending(status: SubmissionView['status']): boolean {
  return status === SubmissionViewStatus.QUEUED
}

function isEvaluationFailure(status: SubmissionView['status'] | CaseResultView['status']): boolean {
  return status === SubmissionViewStatus.SYSTEM_ERROR || status === SubmissionViewStatus.WORKER_CRASH_LIMIT
}

function caseDetail(row: CaseResultView): string {
  if (isEvaluationFailure(row.status)) {
    return '本次评测未能完成，请稍后重新提交'
  }
  return row.detail || '—'
}

async function load(): Promise<void> {
  if ([courseId.value, problemId.value, submissionId.value].some((id) => !Number.isSafeInteger(id) || id <= 0)) {
    loading.value = false
    loadError.value = '提交记录地址无效'
    return
  }
  if (!detail.value) loading.value = true
  loadError.value = ''
  try {
    const { data } = await api.courseProgrammingProblemSubmission(courseId.value, problemId.value, submissionId.value)
    detail.value = data
    stopPolling()
    if (!isPending(data.submission.status)) {
      pollingStartedAt = null
      pollingExhausted.value = false
      return
    }
    const now = Date.now()
    pollingStartedAt ??= now
    const elapsed = now - pollingStartedAt
    if (elapsed >= POLL_LIMIT_MS) {
      pollingExhausted.value = true
      return
    }
    refreshTimer = window.setTimeout(
      () => void load(),
      elapsed < POLL_FAST_WINDOW_MS ? POLL_FAST_MS : POLL_SLOW_MS,
    )
  } catch (error: unknown) {
    stopPolling()
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

async function rejudge(): Promise<void> {
  if (!submission.value) return
  if (!(await confirm('确定重判本次提交吗？现有结果将被清除并重新评测。', '重判'))) return
  rejudging.value = true
  try {
    await api.courseProgrammingProblemRejudgeSubmission(courseId.value, problemId.value, submission.value.id)
    ElMessage.success('已重新排队评测')
    refreshManually()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    rejudging.value = false
  }
}

function refreshManually(): void {
  pollingStartedAt = null
  pollingExhausted.value = false
  void load()
}

onMounted(load)
onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="page">
    <AsyncState :loading="loading" :error="loadError" :empty="!submission" @retry="load">
      <template v-if="submission">
        <section class="panel summary-panel">
          <router-link :to="`/courses/${courseId}/problems/${problemId}`" class="back-link">返回题目</router-link>
          <header class="heading">
            <h1>{{ submission.problemTitle }}</h1>
            <el-tag :type="submissionStatusTagTypes[submission.status]" effect="plain" size="large">
              {{ submissionStatusLabels[submission.status] }}
            </el-tag>
            <el-button
              v-if="session.canEnterManagement && !isPending(submission.status)"
              size="small"
              :loading="rejudging"
              @click="rejudge"
            >
              重判
            </el-button>
          </header>
          <dl class="submission-meta">
            <div>
              <dt>语言</dt>
              <dd>{{ programmingLanguageLabels[submission.language] }}</dd>
            </div>
            <div>
              <dt>运行时间</dt>
              <dd>{{ submission.timeUsedMs === null ? '未记录' : `${submission.timeUsedMs} ms` }}</dd>
            </div>
            <div>
              <dt>运行内存</dt>
              <dd>{{ submission.memoryUsedKb === null ? '未记录' : `${submission.memoryUsedKb} KB` }}</dd>
            </div>
            <div>
              <dt>得分</dt>
              <dd>{{ submission.score === null ? '未记录' : submission.score }}</dd>
            </div>
            <div>
              <dt>提交时间</dt>
              <dd>{{ formatDateTime(submission.submittedAt) }}</dd>
            </div>
          </dl>
        </section>

        <el-alert
          v-if="isPending(submission.status) && pollingExhausted"
          title="评测耗时较长，系统将自动重试，可稍后刷新。"
          type="warning"
          :closable="false"
          show-icon
          class="status-alert"
        >
          <el-button size="small" @click="refreshManually">刷新</el-button>
        </el-alert>
        <el-alert
          v-else-if="isPending(submission.status)"
          title="正在评测，结果会自动更新。"
          type="info"
          :closable="false"
          show-icon
          class="status-alert"
        />
        <el-alert
          v-else-if="isEvaluationFailure(submission.status)"
          title="本次评测未能完成，请稍后重新提交；若持续出现，请联系教师。"
          type="error"
          :closable="false"
          show-icon
          class="status-alert"
        />

        <section class="panel panel-body source-panel">
          <h2>源代码</h2>
          <CodeEditor :model-value="submission.sourceCode" :language="submission.language" readonly />
        </section>
        <section
          v-if="submission.resultDetail && !isEvaluationFailure(submission.status)"
          class="panel panel-body result-panel"
        >
          <h2>编译与运行信息</h2>
          <pre>{{ submission.resultDetail }}</pre>
        </section>
        <section v-if="detail?.cases.length" class="panel panel-body result-panel">
          <h2>测试点结果</h2>
          <el-table :data="detail.cases" row-key="caseId">
            <el-table-column label="测试点" width="110">
              <template #default="{ $index }: { $index: number }">测试点 {{ $index + 1 }}</template>
            </el-table-column>
            <el-table-column label="结果" width="180">
              <template #default="{ row }: { row: CaseResultView }">
                <el-tag :type="submissionStatusTagTypes[row.status]" effect="plain">
                  {{ submissionStatusLabels[row.status] }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="运行时间" width="140">
              <template #default="{ row }: { row: CaseResultView }">{{ row.timeUsedMs }} ms</template>
            </el-table-column>
            <el-table-column label="运行内存" width="150">
              <template #default="{ row }: { row: CaseResultView }">{{ row.memoryUsedKb }} KB</template>
            </el-table-column>
            <el-table-column label="得分" width="110">
              <template #default="{ row }: { row: CaseResultView }">{{ row.score ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="详情" min-width="240">
              <template #default="{ row }: { row: CaseResultView }">{{ caseDetail(row) }}</template>
            </el-table-column>
          </el-table>
        </section>
      </template>
    </AsyncState>
  </div>
</template>

<style scoped>
.back-link {
  display: inline-block;
  margin-bottom: 8px;
  color: var(--el-color-primary);
  font-size: 13px;
}

.summary-panel {
  padding: 22px 26px;
}

.heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.heading h1 {
  margin: 0;
  font-size: 26px;
}

.submission-meta {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 18px;
  margin: 18px 0 0;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}

.submission-meta dt {
  color: var(--text-muted);
  font-size: 13px;
}

.submission-meta dd {
  margin: 6px 0 0;
  font-weight: 600;
}

.status-alert,
.source-panel,
.result-panel {
  margin-top: 18px;
}

.source-panel h2,
.result-panel h2 {
  margin: 0 0 16px;
  font-size: 18px;
}

pre {
  margin: 0;
  white-space: pre-wrap;
}
</style>
