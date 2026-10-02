<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api, errorMessage } from '@/api/client'
import type { AttemptResult, ItemResultView, ItemView, SubmissionSummaryView } from '@/api/generated'
import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'
import type { ResultsTarget } from '@/features/courses/resultsTarget'
import { formatDateTime } from '@/shared/format'
import { programmingLanguageLabels, submissionStatusLabels, submissionStatusTagTypes } from '@/shared/labels'
import { createLatestRequestGuard } from '@/shared/latestRequest'
import { formatGivenAnswer, formatQuestionAnswer, readQuestionOptions } from '@/features/courses/question'

const props = defineProps<{
  courseId: number
  item: ResultsTarget | undefined
  student: { accountId: number; accountName: string } | undefined
}>()

const visible = defineModel<boolean>({ required: true })

const loading = ref(false)
const error = ref('')
const attempts = ref<AttemptResult[]>([])
const questionItems = ref<ItemView[]>([])
const submissions = ref<SubmissionSummaryView[]>([])

const title = computed(() => `${props.student?.accountName ?? ''} · ${props.item?.title ?? ''}`)

const requests = createLatestRequestGuard(
  () => ({
    courseId: props.courseId,
    itemType: props.item?.itemType ?? null,
    contentId: props.item?.contentId ?? null,
    accountId: props.student?.accountId ?? null,
  }),
  (left, right) =>
    left.courseId === right.courseId &&
    left.itemType === right.itemType &&
    left.contentId === right.contentId &&
    left.accountId === right.accountId,
)

async function load(): Promise<void> {
  const request = requests.begin()
  attempts.value = []
  questionItems.value = []
  submissions.value = []
  error.value = ''
  const { courseId, itemType, contentId, accountId } = request.snapshot
  if (contentId === null || accountId === null) return
  loading.value = true
  try {
    if (itemType === 'question') {
      const [attemptList, detail] = await Promise.all([
        api.courseQuestionStudentAttempts(courseId, contentId, accountId),
        api.courseQuestionGetForManagement(courseId, contentId),
      ])
      if (!requests.isCurrent(request)) return
      attempts.value = attemptList.data
      questionItems.value = detail.data.items
    } else if (itemType === 'programming_problem') {
      const list = await api.courseProgrammingProblemStudentSubmissions(courseId, contentId, accountId)
      if (!requests.isCurrent(request)) return
      submissions.value = list.data
    }
  } catch (cause: unknown) {
    if (!requests.isCurrent(request)) return
    error.value = errorMessage(cause)
  } finally {
    if (requests.isCurrent(request)) loading.value = false
  }
}

function itemOf(itemId: number): ItemView | undefined {
  return questionItems.value.find((item) => item.id === itemId)
}

function optionsOf(item: ItemView): string[] {
  return readQuestionOptions(item.type, item.options, `第 ${item.position} 题`)
}

function givenText(entry: ItemResultView): string {
  const item = itemOf(entry.itemId)
  return item ? formatGivenAnswer(item.type, item.options, entry.given) : ''
}

function answerText(entry: ItemResultView): string {
  const item = itemOf(entry.itemId)
  if (!item || entry.answer === null || entry.answer === undefined) return ''
  return formatQuestionAnswer(item.type, item.options, entry.answer)
}

function submissionHref(submission: SubmissionSummaryView): string {
  return `/courses/${props.courseId}/problems/${submission.problemId}/submissions/${submission.id}`
}

watch(visible, (open) => {
  if (open) void load()
  else requests.invalidate()
})
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="860px" append-to-body>
    <div v-loading="loading" class="body">
      <div v-if="error" class="load-error" role="alert">
        <span>{{ error }}</span>
        <el-button size="small" @click="load">重新加载</el-button>
      </div>
      <template v-else-if="item?.itemType === 'question'">
        <el-empty v-if="!loading && attempts.length === 0" description="还没有交卷" :image-size="80" />
        <el-collapse v-else accordion>
          <el-collapse-item
            v-for="(attempt, index) in attempts"
            :key="attempt.attemptId"
            :name="attempt.attemptId"
          >
            <template #title>
              <span class="attempt-title">
                第 {{ attempts.length - index }} 次
                <span class="attempt-score">{{ attempt.score }} / {{ attempt.totalScore }} 分</span>
                <span class="attempt-time">{{ formatDateTime(attempt.submittedAt) }}</span>
              </span>
            </template>
            <section v-for="entry in attempt.items" :key="entry.itemId" class="item-card">
              <header class="item-heading">
                <strong>第 {{ entry.position }} 题</strong>
                <el-tag :type="entry.correct ? 'success' : 'danger'" size="small" effect="dark">
                  {{ entry.correct ? '正确' : '错误' }}
                </el-tag>
                <span class="item-score">{{ entry.score }} / {{ entry.maxScore }} 分</span>
              </header>
              <MarkdownRenderer v-if="itemOf(entry.itemId)" :source="itemOf(entry.itemId)!.stemMarkdown" />
              <ol v-if="itemOf(entry.itemId)?.type === 'single_choice'" class="result-options" type="A">
                <li v-for="(option, optionIndex) in optionsOf(itemOf(entry.itemId)!)" :key="optionIndex">
                  <MarkdownRenderer inline :source="option" />
                </li>
              </ol>
              <div class="answer-comparison">
                <div>
                  <h4>学生答案</h4>
                  <p><MarkdownRenderer inline :source="givenText(entry)" /></p>
                </div>
                <div>
                  <h4>标准答案</h4>
                  <p><MarkdownRenderer inline :source="answerText(entry)" /></p>
                </div>
              </div>
              <template v-if="entry.analysisMarkdown">
                <h4 class="analysis-title">解析</h4>
                <MarkdownRenderer :source="entry.analysisMarkdown" />
              </template>
            </section>
          </el-collapse-item>
        </el-collapse>
      </template>
      <el-table v-else :data="submissions" size="small" empty-text="还没有提交">
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }: { row: SubmissionSummaryView }">
            {{ formatDateTime(row.submittedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="语言" width="110">
          <template #default="{ row }: { row: SubmissionSummaryView }">
            {{ programmingLanguageLabels[row.language] }}
          </template>
        </el-table-column>
        <el-table-column label="结果" width="150">
          <template #default="{ row }: { row: SubmissionSummaryView }">
            <el-tag :type="submissionStatusTagTypes[row.status]" effect="plain" size="small">
              {{ submissionStatusLabels[row.status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="得分" width="90">
          <template #default="{ row }: { row: SubmissionSummaryView }">
            {{ row.score ?? '—' }}
          </template>
        </el-table-column>
        <el-table-column label="耗时 / 内存">
          <template #default="{ row }: { row: SubmissionSummaryView }">
            {{ row.timeUsedMs === null ? '—' : `${row.timeUsedMs} ms` }} /
            {{ row.memoryUsedKb === null ? '—' : `${row.memoryUsedKb} KB` }}
          </template>
        </el-table-column>
        <el-table-column width="110" align="right">
          <template #default="{ row }: { row: SubmissionSummaryView }">
            <el-link type="primary" :href="submissionHref(row)" target="_blank" rel="noopener"
              >查看代码</el-link
            >
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-dialog>
</template>

<style scoped>
.load-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--el-color-danger);
}

.body {
  min-height: 120px;
  max-height: 70vh;
  overflow-y: auto;
}

.attempt-title {
  display: flex;
  align-items: center;
  gap: 16px;
}

.attempt-score {
  font-weight: 600;
}

.attempt-time {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.item-card {
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.item-card:last-child {
  border-bottom: none;
}

.item-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.item-score {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.result-options {
  margin: 8px 0 0;
  padding-left: 28px;
}

.answer-comparison {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 10px;
}

.answer-comparison h4 {
  margin: 0 0 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 500;
}

.answer-comparison p {
  margin: 0;
}

.analysis-title {
  margin: 12px 0 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 500;
}
</style>
