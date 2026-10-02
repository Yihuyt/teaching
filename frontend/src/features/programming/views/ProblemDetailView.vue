<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { api, errorMessage } from '@/api/client'
import {
  SubmitRequestLanguage,
  type ProblemDetailView as ProgrammingProblemDetail,
  type ProblemPersonView,
  type SubmissionSummaryView,
} from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import CodeEditor from '@/features/programming/components/CodeEditor.vue'
import JudgeGuide from '@/features/programming/components/JudgeGuide.vue'
import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'
import { formatDateTime } from '@/shared/format'
import {
  difficultyLabels,
  difficultyTagTypes,
  programmingLanguageLabels,
  submissionStatusLabels,
  submissionStatusTagTypes,
} from '@/shared/labels'
import { createLatestRequestGuard } from '@/shared/latestRequest'

const route = useRoute()
const router = useRouter()
const courseId = computed(() => Number(route.params.courseId))
const problemId = computed(() => Number(route.params.problemId))

function isValidId(value: number): boolean {
  return Number.isSafeInteger(value) && value > 0
}
const loading = ref(true)
const submitting = ref(false)
const loadError = ref('')
const submitError = ref('')
const details = ref<ProgrammingProblemDetail>()
const problem = computed(() => details.value?.problem)
const submissions = ref<SubmissionSummaryView[]>([])
const language = ref<SubmitRequestLanguage>(SubmitRequestLanguage.CPP20)
const sourceCode = ref('')
const guideVisible = ref(false)

const licenseLabels: Readonly<Record<string, string>> = {
  unknown: '未声明',
  'public domain': '公共领域',
  cc0: 'CC0',
  'cc by': 'CC BY',
  'cc by-sa': 'CC BY-SA',
  educational: '仅限教育用途',
  permission: '已获授权',
}

const creditGroups = computed(() => {
  const credits = details.value?.provenance?.credits
  if (!credits) return []
  return [
    { label: '作者', people: credits.authors },
    { label: '贡献者', people: credits.contributors },
    { label: '测试者', people: credits.testers },
    { label: '题包制作', people: credits.packagers },
    { label: '致谢', people: credits.acknowledgements },
    ...Object.entries(credits.translators).map(([locale, people]) => ({
      label: `翻译（${locale}）`,
      people,
    })),
  ].filter((group) => group.people.length > 0)
})

function personLabel(person: ProblemPersonView): string {
  const references = [
    person.orcid ? `ORCID ${person.orcid}` : '',
    person.kattis ? `Kattis ${person.kattis}` : '',
  ].filter(Boolean)
  return references.length === 0 ? person.name : `${person.name}（${references.join('，')}）`
}

function submissionPath(submission: SubmissionSummaryView): string {
  return `/courses/${courseId.value}/problems/${problemId.value}/submissions/${submission.id}`
}

const loadRequests = createLatestRequestGuard(
  () => ({ courseId: courseId.value, problemId: problemId.value }),
  (left, right) => left.courseId === right.courseId && left.problemId === right.problemId,
)

async function load(): Promise<void> {
  if (!isValidId(problemId.value) || !isValidId(courseId.value)) {
    loadError.value = '题目地址无效'
    loading.value = false
    return
  }
  const request = loadRequests.begin()
  loading.value = true
  loadError.value = ''
  try {
    const [detail, mine] = await Promise.all([
      api.courseProgrammingProblemGet(courseId.value, problemId.value),
      api.courseProgrammingProblemMySubmissions(courseId.value, problemId.value),
    ])
    if (!loadRequests.isCurrent(request)) return
    const firstLanguage = detail.data.problem.languages[0]
    if (!firstLanguage) {
      throw new Error('该题目未配置可用的编程语言')
    }
    details.value = detail.data
    submissions.value = mine.data
    language.value = firstLanguage
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) loading.value = false
  }
}

async function submit(): Promise<void> {
  if (!problem.value || sourceCode.value.trim().length === 0) {
    submitError.value = '请输入要提交的源代码'
    return
  }
  if (sourceCode.value.length > 100000) {
    submitError.value = '源代码不能超过 100000 个字符'
    return
  }
  submitting.value = true
  submitError.value = ''
  try {
    const { data } = await api.courseProgrammingProblemSubmit(courseId.value, problem.value.id, {
      language: language.value,
      sourceCode: sourceCode.value,
    })
    ElMessage.success('提交成功，正在等待评测')
    await router.push(`/courses/${courseId.value}/problems/${problem.value.id}/submissions/${data.id}`)
  } catch (error: unknown) {
    submitError.value = errorMessage(error)
  } finally {
    submitting.value = false
  }
}

// 同一路由记录之间只换参数不重建组件:参数变了要重新装载
watch([courseId, problemId], load, { immediate: true })
</script>

<template>
  <div class="page problem-page">
    <AsyncState :loading="loading" :error="loadError" :empty="!details" @retry="load">
      <template v-if="details && problem">
        <header class="panel problem-header">
          <div class="problem-heading">
            <h1>{{ problem.title }}</h1>
            <el-tag :type="difficultyTagTypes[problem.difficulty]" effect="plain">
              {{ difficultyLabels[problem.difficulty] }}
            </el-tag>
          </div>
          <dl>
            <div>
              <dt>每个测试点时间限制</dt>
              <dd>{{ problem.timeLimitMs }} ms</dd>
            </div>
            <div>
              <dt>每个测试点内存限制</dt>
              <dd>{{ problem.memoryLimitMb }} MB</dd>
            </div>
            <div>
              <dt>每个测试点输出限制</dt>
              <dd>{{ problem.outputLimitKb }} KB</dd>
            </div>
          </dl>
        </header>
        <div class="scope-banner">
          <span>当前题目属于课程内容</span>
          <router-link :to="`/courses/${courseId}?tab=outline`">返回课程</router-link>
        </div>
        <div class="problem-grid">
          <div class="content-column">
            <article class="panel statement">
              <h2>题目内容</h2>
              <MarkdownRenderer :source="details.statementMarkdown" />
            </article>
            <section v-if="details.samples.length > 0" class="panel samples">
              <h2>公开样例</h2>
              <div v-for="sample in details.samples" :key="sample.position" class="sample">
                <strong>样例 {{ sample.position }}</strong>
                <div class="sample-grid">
                  <div>
                    <span>输入</span>
                    <pre>{{ sample.input }}</pre>
                  </div>
                  <div>
                    <span>输出</span>
                    <pre>{{ sample.output }}</pre>
                  </div>
                </div>
              </div>
            </section>
            <section v-if="details.provenance" class="panel provenance">
              <h2>题目来源与许可</h2>
              <dl>
                <div>
                  <dt>许可</dt>
                  <dd>{{ licenseLabels[details.provenance.licenseCode] }}</dd>
                </div>
                <div v-if="details.provenance.rightsOwner">
                  <dt>权利人</dt>
                  <dd>{{ details.provenance.rightsOwner }}</dd>
                </div>
                <div v-if="details.provenance.sources.length > 0">
                  <dt>来源</dt>
                  <dd>
                    <template v-for="(source, index) in details.provenance.sources" :key="index">
                      <a v-if="source.url" :href="source.url" target="_blank" rel="noopener noreferrer">
                        {{ source.name }}
                      </a>
                      <span v-else>{{ source.name }}</span>
                    </template>
                  </dd>
                </div>
                <div v-for="group in creditGroups" :key="group.label">
                  <dt>{{ group.label }}</dt>
                  <dd>{{ group.people.map(personLabel).join('、') }}</dd>
                </div>
              </dl>
            </section>
          </div>
          <div class="submit-column">
            <section class="panel submit-panel">
              <div class="submit-panel__header">
                <h2>提交代码</h2>
                <el-select v-model="language" style="width: 160px">
                  <el-option
                    v-for="item in problem.languages"
                    :key="item"
                    :label="programmingLanguageLabels[item]"
                    :value="item"
                  />
                </el-select>
              </div>
              <CodeEditor v-model="sourceCode" :language="language" />
              <el-alert
                v-if="submitError"
                :title="submitError"
                type="error"
                :closable="false"
                class="submit-error"
              />
              <div class="submit-actions">
                <el-button text type="primary" @click="guideVisible = !guideVisible">
                  {{ guideVisible ? '收起评测说明' : '评测说明' }}
                </el-button>
                <el-button type="primary" :loading="submitting" @click="submit">提交评测</el-button>
              </div>
              <JudgeGuide v-if="guideVisible" class="guide" />
            </section>
            <section class="panel submissions-panel">
              <h2>我的提交</h2>
              <p v-if="submissions.length === 0" class="muted">还没有提交过</p>
              <el-table v-else :data="submissions" row-key="id" size="small">
                <el-table-column label="提交时间" min-width="170">
                  <template #default="{ row }: { row: SubmissionSummaryView }">
                    {{ formatDateTime(row.submittedAt) }}
                  </template>
                </el-table-column>
                <el-table-column label="语言" width="120">
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
                <el-table-column label="得分" width="80">
                  <template #default="{ row }: { row: SubmissionSummaryView }">{{ row.score ?? '—' }}</template>
                </el-table-column>
                <el-table-column width="80">
                  <template #default="{ row }: { row: SubmissionSummaryView }">
                    <router-link :to="submissionPath(row)">详情</router-link>
                  </template>
                </el-table-column>
              </el-table>
            </section>
          </div>
        </div>
      </template>
    </AsyncState>
  </div>
</template>

<style scoped>
.problem-page {
  width: min(1680px, calc(100% - 48px));
}

.problem-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  margin-bottom: 22px;
  padding: 24px 28px;
}

.problem-heading {
  display: flex;
  align-items: center;
  gap: 14px;
}

.problem-header h1 {
  margin: 0;
  font-size: 26px;
}

.problem-header dl {
  display: flex;
  gap: 32px;
  margin: 0;
}

.problem-header dl div {
  min-width: 150px;
  padding-left: 20px;
  border-left: 1px solid var(--border);
}

.problem-header dt {
  color: var(--text-muted);
  font-size: 12px;
}

.problem-header dd {
  margin: 5px 0 0;
  font-weight: 700;
}

.problem-grid {
  display: grid;
  grid-template-columns: minmax(440px, 0.9fr) minmax(540px, 1.1fr);
  gap: 20px;
  align-items: start;
}

.content-column,
.submit-column {
  display: grid;
  gap: 20px;
}

.scope-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: -4px 0 20px;
  padding: 14px 18px;
  border: 1px solid #b2ddff;
  border-radius: var(--radius-control);
  color: #1849a9;
  background: #eff8ff;
}

.statement,
.samples,
.provenance,
.submit-panel,
.submissions-panel {
  padding: 26px;
}

.statement h2,
.samples h2,
.provenance h2,
.submit-panel h2,
.submissions-panel h2 {
  margin: 0 0 18px;
  font-size: 19px;
}

.sample {
  padding: 16px 0;
  border-top: 1px solid var(--border);
}

.sample-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-top: 10px;
}

.sample-grid span {
  display: block;
  margin-bottom: 6px;
  color: var(--text-muted);
  font-size: 12px;
}

.sample-grid pre {
  min-height: 54px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border-radius: 8px;
  background: #f7f8fa;
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  white-space: pre-wrap;
}

.provenance dl {
  margin: 0;
}

.provenance dl > div {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 12px;
  padding: 9px 0;
  border-top: 1px solid var(--border);
}

.provenance dt {
  color: var(--text-muted);
}

.provenance dd {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  margin: 0;
}

.submit-panel__header,
.submit-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.submit-panel__header h2 {
  margin: 0;
}

.submit-error,
.submit-actions {
  margin-top: 16px;
}

.guide {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--border);
}

.submissions-panel .muted {
  margin: 0;
  color: var(--text-muted);
}
</style>
