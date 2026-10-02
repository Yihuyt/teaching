<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ElMessage } from 'element-plus'

import { api, ApiProblem, errorMessage } from '@/api/client'
import type { AttemptResult, JsonNode, LearningItemView, LearningQuestionView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import { createLatestRequestGuard } from '@/shared/latestRequest'
import { confirm } from '@/shared/dialogs'
import { formatDateTime } from '@/shared/format'
import { questionTypeLabels } from '@/shared/labels'
import { formatGivenAnswer, formatQuestionAnswer, readQuestionOptions } from '@/features/courses/question'

const route = useRoute()
const router = useRouter()
const courseId = computed(() => Number(route.params.courseId))
const questionId = computed(() => Number(route.params.questionId))
const loading = ref(true)
const loadError = ref('')
const question = ref<LearningQuestionView>()
const loadRequests = createLatestRequestGuard(
  () => ({ courseId: courseId.value, questionId: questionId.value }),
  (left, right) => left.courseId === right.courseId && left.questionId === right.questionId,
)

type Stage = 'overview' | 'answering' | 'result'
const stage = ref<Stage>('overview')
const attemptId = ref<number | null>(null)
const deadlineAt = ref<number | null>(null)
const answers = ref<Record<number, string | boolean | undefined>>({})
const submitting = ref(false)
const actionError = ref('')
const result = ref<AttemptResult>()
const history = ref<AttemptResult[]>([])
const historyLoading = ref(false)
const remainingSeconds = ref<number | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

function hasValidRouteIds(): boolean {
  return (
    Number.isSafeInteger(courseId.value) &&
    courseId.value > 0 &&
    Number.isSafeInteger(questionId.value) &&
    questionId.value > 0
  )
}

function options(item: LearningItemView): string[] {
  return readQuestionOptions(item.type, item.options, `第 ${item.position} 题`)
}

const answeredCount = computed(() => {
  if (!question.value) return 0
  return question.value.items.filter((item) => {
    const value = answers.value[item.id]
    return value !== undefined && value !== ''
  }).length
})

const remainingText = computed(() => {
  if (remainingSeconds.value === null) return ''
  const total = Math.max(0, remainingSeconds.value)
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
})

function stopTimer(): void {
  if (timer) clearInterval(timer)
  timer = null
  remainingSeconds.value = null
}

function startTimer(): void {
  stopTimer()
  if (deadlineAt.value === null) return
  const deadline = deadlineAt.value
  const tick = (): void => {
    remainingSeconds.value = Math.ceil((deadline - Date.now()) / 1000)
    if (remainingSeconds.value <= 0) {
      stopTimer()
      remainingSeconds.value = 0
      void submit(true)
    }
  }
  tick()
  timer = setInterval(tick, 1000)
}

async function load(): Promise<void> {
  const request = loadRequests.begin()
  stopTimer()
  question.value = undefined
  result.value = undefined
  history.value = []
  loadError.value = ''
  actionError.value = ''
  stage.value = 'overview'
  if (!hasValidRouteIds()) {
    loadError.value = '当前课程或试题地址无效'
    loading.value = false
    return
  }
  loading.value = true
  try {
    const response = await api.courseQuestionGet(request.snapshot.courseId, request.snapshot.questionId)
    if (!loadRequests.isCurrent(request)) return
    if (
      response.data.courseId !== request.snapshot.courseId ||
      response.data.id !== request.snapshot.questionId
    ) {
      throw new Error('加载的试题不属于当前课程')
    }
    response.data.items.forEach(options)
    question.value = response.data
    if (response.data.activeAttempt) {
      resume(response.data.activeAttempt.id, response.data.activeAttempt.remainingSeconds)
    } else {
      await loadHistory(request)
    }
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) {
      loading.value = false
    }
  }
}

/** 倒计时按服务端算出的剩余秒数,从收到响应的那一刻起算——不信任本机时钟 */
function resume(id: number, remaining: number | null): void {
  // 只有换了一次作答才清空已填内容;同一次作答重入(如交卷失败后)保留已答
  if (attemptId.value !== id) answers.value = {}
  attemptId.value = id
  deadlineAt.value = remaining === null ? null : Date.now() + remaining * 1000
  actionError.value = ''
  stage.value = 'answering'
  startTimer()
}

async function start(): Promise<void> {
  if (!question.value) return
  actionError.value = ''
  try {
    const response = await api.courseQuestionStartAttempt(courseId.value, questionId.value)
    resume(response.data.id, response.data.remainingSeconds)
  } catch (error: unknown) {
    actionError.value = errorMessage(error)
  }
}

async function submit(auto = false): Promise<void> {
  if (!question.value || attemptId.value === null || submitting.value) return
  if (!auto && answeredCount.value < question.value.items.length) {
    const confirmed = await confirm(
      `还有 ${question.value.items.length - answeredCount.value} 题未作答，确定交卷？`,
      '交卷',
    )
    if (!confirmed) return
  }
  submitting.value = true
  actionError.value = ''
  const requestedAttempt = attemptId.value
  try {
    const response = await api.courseQuestionSubmitAttempt(
      courseId.value,
      questionId.value,
      requestedAttempt,
      {
        answers: question.value.items.map((item) => {
          const value = answers.value[item.id]
          return { itemId: item.id, answer: (value === undefined || value === '' ? null : value) as JsonNode }
        }),
      },
    )
    stopTimer()
    result.value = response.data
    stage.value = 'result'
    attemptId.value = null
    await reloadSummary()
  } catch (error: unknown) {
    // 交卷失败:保留已答内容与作答界面,学生可手动重试;逾期的提交由服务端按空卷结算并正常返回结果。
    // 本次作答已不存在(另一标签页已交卷 / 试题被移出课程内容)时重新装载,不把学生困在作答页
    if (error instanceof ApiProblem && (error.status === 404 || error.status === 409)) {
      ElMessage.error(errorMessage(error))
      await load()
      return
    }
    actionError.value = errorMessage(error)
  } finally {
    submitting.value = false
  }
}

async function leave(): Promise<void> {
  if (
    stage.value === 'answering' &&
    !(await confirm('作答尚未交卷，离开后可回来续答。确定离开吗？', '离开'))
  ) {
    return
  }
  await router.push(`/courses/${courseId.value}`)
}

async function reloadSummary(): Promise<void> {
  try {
    const response = await api.courseQuestionGet(courseId.value, questionId.value)
    question.value = response.data
  } catch (error: unknown) {
    actionError.value = errorMessage(error)
  }
}

async function loadHistory(request = loadRequests.begin()): Promise<void> {
  historyLoading.value = true
  try {
    const response = await api.courseQuestionMyAttempts(
      request.snapshot.courseId,
      request.snapshot.questionId,
    )
    if (!loadRequests.isCurrent(request)) return
    history.value = response.data
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    actionError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) historyLoading.value = false
  }
}

function showResult(attempt: AttemptResult): void {
  result.value = attempt
  stage.value = 'result'
}

function backToOverview(): void {
  result.value = undefined
  stage.value = 'overview'
  void loadHistory()
}

function itemOf(itemId: number): LearningItemView | undefined {
  return question.value?.items.find((item) => item.id === itemId)
}

function standardAnswerText(itemId: number, answer: unknown): string {
  const item = itemOf(itemId)
  if (!item || answer === null || answer === undefined) return ''
  return formatQuestionAnswer(item.type, item.options, answer)
}

function givenAnswerText(itemId: number, given: unknown): string {
  const item = itemOf(itemId)
  return item ? formatGivenAnswer(item.type, item.options, given) : ''
}

watch([courseId, questionId], load, { immediate: true })
onBeforeUnmount(stopTimer)
</script>

<template>
  <div class="page question-page">
    <PageHeader :title="question?.title ?? '试题'">
      <template #actions>
        <el-button @click="leave">返回课程</el-button>
      </template>
    </PageHeader>

    <AsyncState :loading="loading" :error="loadError" :empty="!question" @retry="load">
      <template v-if="question">
        <section v-if="stage === 'overview'" class="panel overview">
          <dl class="facts">
            <div>
              <dt>题数</dt>
              <dd>{{ question.items.length }}</dd>
            </div>
            <div>
              <dt>总分</dt>
              <dd>{{ question.totalScore }}</dd>
            </div>
            <div>
              <dt>限时</dt>
              <dd>{{ question.timeLimitMinutes ? `${question.timeLimitMinutes} 分钟` : '不限' }}</dd>
            </div>
            <div>
              <dt>作答</dt>
              <dd>{{ question.allowRetake ? '可重做，取最高分' : '只能一次' }}</dd>
            </div>
            <div>
              <dt>我的最高分</dt>
              <dd>{{ question.bestScore === null ? '—' : question.bestScore }}</dd>
            </div>
          </dl>
          <div class="overview-actions">
            <el-button v-if="question.canStart" type="primary" size="large" @click="start">
              {{ question.attemptCount > 0 ? '再做一次' : '开始作答' }}
            </el-button>
            <span v-else class="overview-note">已交卷</span>
            <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" show-icon />
          </div>
          <template v-if="history.length">
            <h3>作答记录</h3>
            <el-table v-loading="historyLoading" :data="history" size="small">
              <el-table-column label="交卷时间" width="200">
                <template #default="{ row }: { row: AttemptResult }">{{
                  formatDateTime(row.submittedAt)
                }}</template>
              </el-table-column>
              <el-table-column label="得分" width="140">
                <template #default="{ row }: { row: AttemptResult }"
                  >{{ row.score }} / {{ row.totalScore }}</template
                >
              </el-table-column>
              <el-table-column label="操作">
                <template #default="{ row }: { row: AttemptResult }">
                  <el-button link type="primary" @click="showResult(row)">查看</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </section>

        <template v-else-if="stage === 'answering'">
          <div class="answer-bar panel">
            <span>已答 {{ answeredCount }} / {{ question.items.length }}</span>
            <span
              v-if="remainingSeconds !== null"
              class="countdown"
              :class="{ urgent: remainingSeconds <= 60 }"
              role="timer"
              :aria-live="remainingSeconds <= 60 ? 'polite' : 'off'"
            >
              剩余 {{ remainingText }}
            </span>
            <el-button type="primary" :loading="submitting" @click="submit()">交卷</el-button>
          </div>
          <el-alert
            v-if="actionError"
            :title="actionError"
            type="error"
            :closable="false"
            show-icon
            class="bar-alert"
          />
          <section v-for="item in question.items" :key="item.id" class="panel item-card">
            <header class="item-heading">
              <h3 class="item-title">第 {{ item.position }} 题</h3>
              <el-tag size="small" effect="plain">{{ questionTypeLabels[item.type] }}</el-tag>
              <span class="item-score">{{ item.score }} 分</span>
            </header>
            <MarkdownRenderer :source="item.stemMarkdown" />
            <el-radio-group
              v-if="item.type === 'single_choice'"
              v-model="answers[item.id]"
              class="answer-options"
            >
              <el-radio v-for="(option, index) in options(item)" :key="index" :value="option" border>
                {{ String.fromCharCode(65 + index) }}. <MarkdownRenderer inline :source="option" />
              </el-radio>
            </el-radio-group>
            <el-input
              v-else-if="item.type === 'fill_in_blank'"
              v-model="answers[item.id]"
              maxlength="200"
              placeholder="填入空格处的词或短语"
              class="answer-blank"
            />
            <el-radio-group v-else v-model="answers[item.id]" class="answer-options horizontal">
              <el-radio :value="true" border>正确</el-radio>
              <el-radio :value="false" border>错误</el-radio>
            </el-radio-group>
          </section>
          <div class="bottom-actions">
            <el-button type="primary" size="large" :loading="submitting" @click="submit()">交卷</el-button>
          </div>
        </template>

        <template v-else-if="result">
          <section class="panel result-summary">
            <div class="score">
              <span class="score-value">{{ result.score }}</span>
              <span class="score-total">/ {{ result.totalScore }}</span>
            </div>
            <el-alert
              v-if="result.overdue"
              title="超过作答时限，本次按空卷计分"
              type="warning"
              :closable="false"
              show-icon
            />
            <div class="result-meta">
              <span>交卷时间 {{ formatDateTime(result.submittedAt) }}</span>
              <span
                >答对 {{ result.items.filter((i) => i.correct).length }} / {{ result.items.length }} 题</span
              >
            </div>
            <div class="result-actions">
              <el-button @click="backToOverview">返回概览</el-button>
              <el-button v-if="question.canStart && question.allowRetake" type="primary" @click="start"
                >再做一次</el-button
              >
            </div>
            <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" show-icon />
          </section>
          <section v-for="entry in result.items" :key="entry.itemId" class="panel item-card">
            <header class="item-heading">
              <h3 class="item-title">第 {{ entry.position }} 题</h3>
              <el-tag :type="entry.correct ? 'success' : 'danger'" size="small" effect="dark">
                {{ entry.correct ? '正确' : '错误' }}
              </el-tag>
              <span class="item-score">{{ entry.score }} / {{ entry.maxScore }} 分</span>
            </header>
            <MarkdownRenderer v-if="itemOf(entry.itemId)" :source="itemOf(entry.itemId)!.stemMarkdown" />
            <ol v-if="itemOf(entry.itemId)?.type === 'single_choice'" class="result-options" type="A">
              <li v-for="(option, optionIndex) in options(itemOf(entry.itemId)!)" :key="optionIndex">
                <MarkdownRenderer inline :source="option" />
              </li>
            </ol>
            <div class="answer-comparison">
              <div>
                <h4>你的答案</h4>
                <p><MarkdownRenderer inline :source="givenAnswerText(entry.itemId, entry.given)" /></p>
              </div>
              <div v-if="result.revealAnswers">
                <h4>标准答案</h4>
                <p><MarkdownRenderer inline :source="standardAnswerText(entry.itemId, entry.answer)" /></p>
              </div>
            </div>
            <template v-if="result.revealAnswers && entry.analysisMarkdown">
              <h4>解析</h4>
              <MarkdownRenderer :source="entry.analysisMarkdown" />
            </template>
          </section>
        </template>
      </template>
    </AsyncState>
  </div>
</template>

<style scoped>
.question-page {
  width: min(1180px, calc(100% - 96px));
}

.overview {
  padding: 28px 32px;
}

.facts {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16px;
  margin: 0 0 24px;
}

.facts dt {
  color: var(--text-secondary);
  font-size: 13px;
}

.facts dd {
  margin: 4px 0 0;
  font-size: 20px;
  font-weight: 600;
}

.overview-actions {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}

.overview-note {
  color: var(--text-muted);
}

.overview h3 {
  margin: 0 0 10px;
  font-size: 16px;
}

.answer-bar {
  position: sticky;
  top: 8px;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 12px 20px;
  margin-bottom: 16px;
}

.answer-bar .el-button {
  margin-left: auto;
}

.countdown {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.countdown.urgent {
  color: var(--el-color-danger);
}

.bar-alert {
  margin-bottom: 12px;
}

.item-card {
  padding: 22px 28px;
  margin-bottom: 16px;
}

.item-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.item-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.item-score {
  margin-left: auto;
  color: var(--text-secondary);
  font-size: 13px;
}

.answer-options {
  display: grid;
  gap: 10px;
  margin-top: 18px;
}

.answer-options.horizontal {
  display: flex;
}

.answer-options :deep(.el-radio) {
  width: 100%;
  height: auto;
  min-height: 44px;
  margin: 0;
  padding: 10px 14px;
  white-space: normal;
}

.answer-options.horizontal :deep(.el-radio) {
  width: 160px;
}

.answer-blank {
  max-width: 420px;
  margin-top: 14px;
}

.bottom-actions {
  display: flex;
  justify-content: center;
  padding: 8px 0 24px;
}

.result-summary {
  padding: 24px 32px;
  margin-bottom: 16px;
}

.score {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.score-value {
  font-size: 40px;
  font-weight: 700;
  color: var(--el-color-primary);
}

.score-total {
  color: var(--text-secondary);
  font-size: 16px;
}

.result-meta {
  display: flex;
  gap: 20px;
  margin: 8px 0 16px;
  color: var(--text-secondary);
  font-size: 13px;
}

.result-actions {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}

.result-options {
  margin: 10px 0;
  padding-left: 28px;
}

.answer-comparison {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-top: 14px;
}

.answer-comparison > div {
  padding: 12px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
}

.answer-comparison h4,
.item-card h4 {
  margin: 0 0 8px;
  color: var(--text-secondary);
  font-size: 13px;
}

.item-card h4 {
  margin-top: 16px;
}

.answer-comparison p {
  margin: 0;
  white-space: pre-wrap;
}
</style>
