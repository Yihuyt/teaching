<script setup lang="ts">
/**
 * 课程内容里试题 / 编程题的成绩:每个作答 / 提交过的学生一行。
 * 成绩属于课程内容(学生只能作答编排进课程内容的内容),入口在课程内容条目上;
 * 每行「查看」打开该学生的具体作答 / 提交。学情的「内容」页签也从这里进。
 */
import { ref, watch } from 'vue'
import { api, errorMessage } from '@/api/client'
import StudentResultDetailDialog from '@/features/courses/components/StudentResultDetailDialog.vue'
import type { ResultsTarget } from '@/features/courses/resultsTarget'
import type { StudentResultView, StudentSubmissionResultView } from '@/api/generated'
import { formatDateTime } from '@/shared/format'
import { createLatestRequestGuard } from '@/shared/latestRequest'

const props = defineProps<{
  courseId: number
  item: ResultsTarget | undefined
}>()

const visible = defineModel<boolean>({ required: true })

const loading = ref(false)
const error = ref('')
const questionResults = ref<StudentResultView[]>([])
const questionTotalScore = ref<number | null>(null)
const problemResults = ref<StudentSubmissionResultView[]>([])
const detailVisible = ref(false)
const detailStudent = ref<{ accountId: number; accountName: string }>()

function openDetail(student: { accountId: number; accountName: string }): void {
  detailStudent.value = { accountId: student.accountId, accountName: student.accountName }
  detailVisible.value = true
}

const requests = createLatestRequestGuard(
  () => ({
    courseId: props.courseId,
    itemType: props.item?.itemType ?? null,
    contentId: props.item?.contentId ?? null,
  }),
  (left, right) =>
    left.courseId === right.courseId &&
    left.itemType === right.itemType &&
    left.contentId === right.contentId,
)

async function load(): Promise<void> {
  const request = requests.begin()
  questionResults.value = []
  problemResults.value = []
  questionTotalScore.value = null
  error.value = ''
  const { courseId, itemType, contentId } = request.snapshot
  if (contentId === null) return
  loading.value = true
  try {
    if (itemType === 'question') {
      const [results, detail] = await Promise.all([
        api.courseQuestionResults(courseId, contentId),
        api.courseQuestionGetForManagement(courseId, contentId),
      ])
      if (!requests.isCurrent(request)) return
      questionResults.value = results.data
      questionTotalScore.value = detail.data.totalScore
    } else if (itemType === 'programming_problem') {
      const results = await api.courseProgrammingProblemResults(courseId, contentId)
      if (!requests.isCurrent(request)) return
      problemResults.value = results.data
    }
  } catch (cause: unknown) {
    if (!requests.isCurrent(request)) return
    error.value = errorMessage(cause)
  } finally {
    if (requests.isCurrent(request)) loading.value = false
  }
}

watch(visible, (open) => {
  if (open) void load()
  else {
    requests.invalidate()
    detailVisible.value = false
  }
})
</script>

<template>
  <el-dialog v-model="visible" :title="`成绩 · ${item?.title ?? ''}`" width="720px">
    <div v-if="error" class="load-error" role="alert">
      <span>{{ error }}</span>
      <el-button size="small" @click="load">重新加载</el-button>
    </div>
    <el-table
      v-else-if="item?.itemType === 'question'"
      v-loading="loading"
      :data="questionResults"
      size="small"
      empty-text="还没有学生交卷"
    >
      <el-table-column prop="accountName" label="学生" width="160" />
      <el-table-column prop="attemptCount" label="作答次数" width="110" />
      <el-table-column label="最高分" width="140">
        <template #default="{ row }: { row: StudentResultView }">
          {{ questionTotalScore === null ? row.bestScore : `${row.bestScore} / ${questionTotalScore}` }}
        </template>
      </el-table-column>
      <el-table-column label="最近交卷">
        <template #default="{ row }: { row: StudentResultView }">
          {{ formatDateTime(row.lastSubmittedAt) }}
        </template>
      </el-table-column>
      <el-table-column width="80" align="right">
        <template #default="{ row }: { row: StudentResultView }">
          <el-button link type="primary" @click="openDetail(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-table v-else v-loading="loading" :data="problemResults" size="small" empty-text="还没有学生提交">
      <el-table-column prop="accountName" label="学生" width="160" />
      <el-table-column prop="submissionCount" label="提交次数" width="110" />
      <el-table-column label="是否通过" width="110">
        <template #default="{ row }: { row: StudentSubmissionResultView }">
          <el-tag :type="row.accepted ? 'success' : 'info'" effect="plain" size="small">
            {{ row.accepted ? '通过' : '未通过' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最高分" width="110">
        <template #default="{ row }: { row: StudentSubmissionResultView }">
          {{ row.bestScore ?? '—' }}
        </template>
      </el-table-column>
      <el-table-column label="最近提交">
        <template #default="{ row }: { row: StudentSubmissionResultView }">
          {{ formatDateTime(row.lastSubmittedAt) }}
        </template>
      </el-table-column>
      <el-table-column width="80" align="right">
        <template #default="{ row }: { row: StudentSubmissionResultView }">
          <el-button link type="primary" @click="openDetail(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>
    <StudentResultDetailDialog
      v-model="detailVisible"
      :course-id="courseId"
      :item="item"
      :student="detailStudent"
    />
  </el-dialog>
</template>

<style scoped>
.load-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 0;
  color: var(--el-color-danger);
}
</style>
