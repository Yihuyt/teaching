<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Delete, Plus, Upload } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'
import {
  ProblemDraftRequestDifficulty,
  ProblemDraftRequestLanguagesItem,
  type ProblemDraftRequest,
  type ProblemManagementDetailView,
} from '@/api/generated'
import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'
import CourseProblemPackageImportDialog from '@/features/programming/components/CourseProblemPackageImportDialog.vue'
import { useUnsavedGuard } from '@/shared/composables/useUnsavedGuard'
import { difficultyLabels, programmingLanguageLabels } from '@/shared/labels'

interface Pair {
  input: string
  output: string
}

interface ProblemForm {
  title: string
  statementMarkdown: string
  difficulty: ProblemDraftRequestDifficulty
  timeLimitMs: number
  memoryLimitMb: number
  outputLimitKb: number
  languages: ProblemDraftRequestLanguagesItem[]
  samples: Pair[]
}

const route = useRoute()
const sourceTab = route.query.from === 'outline' ? 'outline' : 'resources'
const router = useRouter()
const courseId = computed(() => Number(route.params.courseId))
const problemId = computed(() => {
  const raw = route.params.problemId
  return typeof raw === 'string' && raw ? Number(raw) : null
})
const routeValid = computed(
  () =>
    Number.isSafeInteger(courseId.value) &&
    courseId.value > 0 &&
    (problemId.value === null || (Number.isSafeInteger(problemId.value) && problemId.value > 0)),
)

const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const saveError = ref('')
const preview = ref(false)
const detail = ref<ProblemManagementDetailView>()
const form = ref<ProblemForm>(emptyForm())
const importDialog = ref<InstanceType<typeof CourseProblemPackageImportDialog>>()
const cases = ref<Pair[]>([{ input: '', output: '' }])
let snapshot = ''

/** 已有提交:题面 / 难度 / 评测配置 / 测试数据全部锁定(后端同规则) */
const locked = computed(() => (detail.value?.submissionCount ?? 0) > 0)
/** 题目包导入的题:题面 / 限制 / 样例 / 测试数据随包固定,只能改难度 */
const imported = computed(() => detail.value?.details.provenance != null)
const testcaseEditable = computed(() => !imported.value && !locked.value)
const testcaseReady = computed(() => detail.value?.testcaseConfirmedAt != null)

function emptyForm(): ProblemForm {
  return {
    title: '',
    statementMarkdown: '',
    difficulty: ProblemDraftRequestDifficulty.easy,
    timeLimitMs: 1000,
    memoryLimitMb: 256,
    outputLimitKb: 1024,
    languages: [ProblemDraftRequestLanguagesItem.CPP20],
    samples: [{ input: '', output: '' }],
  }
}

function formFrom(view: ProblemManagementDetailView): ProblemForm {
  const problem = view.details.problem
  return {
    title: problem.title,
    statementMarkdown: view.details.statementMarkdown,
    difficulty: problem.difficulty,
    timeLimitMs: problem.timeLimitMs,
    memoryLimitMb: problem.memoryLimitMb,
    outputLimitKb: problem.outputLimitKb,
    languages: [...problem.languages],
    samples: view.details.samples.map((sample) => ({ input: sample.input, output: sample.output })),
  }
}

function payload(): ProblemDraftRequest {
  if (!form.value.title.trim()) throw new Error('请填写题目名称')
  if (!form.value.statementMarkdown.trim()) throw new Error('请填写题面')
  if (form.value.languages.length === 0) throw new Error('至少勾选一种语言')
  return {
    title: form.value.title.trim(),
    statementMarkdown: form.value.statementMarkdown,
    difficulty: form.value.difficulty,
    timeLimitMs: form.value.timeLimitMs,
    memoryLimitMb: form.value.memoryLimitMb,
    outputLimitKb: form.value.outputLimitKb,
    languages: form.value.languages,
    samples: form.value.samples.map((sample) => ({ input: sample.input, output: sample.output })),
  }
}

function state(): string {
  return JSON.stringify({ form: form.value, cases: cases.value })
}

function markClean(): void {
  snapshot = state()
}

function isDirty(): boolean {
  return state() !== snapshot
}

function casesDirty(): boolean {
  const saved = JSON.parse(snapshot) as { cases: Pair[] }
  return JSON.stringify(cases.value) !== JSON.stringify(saved.cases)
}

async function loadCases(id: number): Promise<void> {
  const rows = (await api.courseProgrammingProblemTestcases(courseId.value, id)).data
  cases.value = rows.length
    ? rows.map((row) => ({ input: row.input, output: row.output }))
    : [{ input: '', output: '' }]
}

/** 当前表单对应的题 id(新建为 null);保存后换址时用来跳过重新装载 */
const loadedProblemId = ref<number | null>(null)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  if (!routeValid.value) {
    loadError.value = '无效的题目地址'
    loading.value = false
    return
  }
  try {
    if (problemId.value !== null) {
      detail.value = (
        await api.courseProgrammingProblemGetForManagement(courseId.value, problemId.value)
      ).data
      form.value = formFrom(detail.value)
      await loadCases(problemId.value)
    } else {
      detail.value = undefined
      form.value = emptyForm()
      cases.value = [{ input: '', output: '' }]
    }
    loadedProblemId.value = problemId.value
    markClean()
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

async function saveProblem(body: ProblemDraftRequest): Promise<number> {
  if (problemId.value !== null) {
    detail.value = (await api.courseProgrammingProblemUpdate(courseId.value, problemId.value, body)).data
    return problemId.value
  }
  const created = (await api.courseProgrammingProblemCreate(courseId.value, body)).data
  detail.value = created
  loadedProblemId.value = created.details.problem.id
  await router.replace(
    `/focus/admin/courses/${courseId.value}/programming-problems/${created.details.problem.id}/edit`,
  )
  return created.details.problem.id
}

async function saveTestcases(id: number): Promise<void> {
  if (!testcaseEditable.value) return
  const filled = cases.value.filter((row) => row.input.trim() || row.output.trim())
  if (!casesDirty()) return
  if (filled.length === 0) {
    throw new Error('至少要有一个测试点')
  }
  if (filled.some((row) => !row.input.trim() || !row.output.trim())) {
    throw new Error('每个测试点都要有输入和输出')
  }
  detail.value = (
    await api.courseProgrammingProblemCreateManualTestcasePackage(courseId.value, id, { cases: filled })
  ).data
}

async function persist(): Promise<boolean> {
  let body: ProblemDraftRequest
  try {
    body = payload()
  } catch (error: unknown) {
    saveError.value = errorMessage(error)
    return false
  }
  saving.value = true
  saveError.value = ''
  try {
    const id = await saveProblem(body)
    await saveTestcases(id)
    form.value = formFrom(detail.value!)
    await loadCases(id)
    markClean()
    return true
  } catch (error: unknown) {
    saveError.value = errorMessage(error)
    return false
  } finally {
    saving.value = false
  }
}

async function save(): Promise<void> {
  if (await persist()) ElMessage.success('已保存')
}

function addPair(list: Pair[], limit: number): void {
  if (list.length < limit) list.push({ input: '', output: '' })
}

async function onImported(ids: number[]): Promise<void> {
  markClean()
  const [only] = ids
  if (ids.length === 1 && only !== undefined) {
    // 换址前先记下目标题,参数监听不再重复装载;随后显式装载一次
    loadedProblemId.value = only
    await router.replace(`/focus/admin/courses/${courseId.value}/programming-problems/${only}/edit`)
    await load()
    return
  }
  await router.push(`/admin/courses/${courseId.value}?tab=${sourceTab}`)
}

async function back(): Promise<void> {
  await router.push(`/admin/courses/${courseId.value}?tab=${sourceTab}`)
}

useUnsavedGuard(isDirty, {
  allowTo: (to) =>
    to.name === 'admin-programming-problem-edit' && Number(to.params.problemId) === loadedProblemId.value,
})

onMounted(() => {
  void load()
})
// 同一路由记录之间前进 / 后退只换参数不重建组件:参数变了要重新装载;新建保存后的换址不算
watch([courseId, problemId], () => {
  if (problemId.value !== loadedProblemId.value) void load()
})
</script>

<template>
  <div class="editor">
    <header class="editor-header">
      <el-button link :icon="ArrowLeft" @click="back">{{
        sourceTab === 'outline' ? '返回课程内容' : '返回资料库'
      }}</el-button>
      <el-input
        v-model="form.title"
        class="title-input"
        maxlength="255"
        placeholder="题目名称"
        aria-label="题目名称"
        :disabled="imported || locked || loading || loadError !== ''"
      />
      <div class="header-actions">
        <el-tag v-if="locked" type="warning" effect="plain">已有提交，题目已锁定</el-tag>
        <el-button v-if="!detail" :icon="Upload" @click="importDialog?.open()">导入题目包</el-button>
        <el-button type="primary" :loading="saving" :disabled="loading || loadError !== ''" @click="save">
          保存
        </el-button>
      </div>
    </header>

    <div v-if="loading" class="editor-loading">加载中…</div>
    <div v-else-if="loadError" class="editor-loading">{{ loadError }}</div>
    <template v-else>
      <el-alert
        v-if="saveError"
        :title="saveError"
        type="error"
        :closable="false"
        show-icon
        class="save-alert"
      />

      <main class="panel">
        <section class="block">
          <div class="block-head">
            <h3>题面</h3>
            <el-radio-group v-model="preview" size="small">
              <el-radio-button :value="false">编辑</el-radio-button>
              <el-radio-button :value="true">预览</el-radio-button>
            </el-radio-group>
          </div>
          <MarkdownRenderer
            v-if="preview"
            :source="form.statementMarkdown || '（空）'"
            class="statement-preview"
          />
          <el-input
            v-else
            v-model="form.statementMarkdown"
            type="textarea"
            :disabled="imported || locked"
            :rows="14"
            placeholder="题面"
          />
        </section>

        <section class="block">
          <h3>评测配置</h3>
          <div class="limits">
            <el-form-item label="难度">
              <el-select v-model="form.difficulty" :disabled="locked">
                <el-option
                  v-for="(label, value) in difficultyLabels"
                  :key="value"
                  :label="label"
                  :value="value"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="时间限制（ms）">
              <el-input-number
                v-model="form.timeLimitMs"
                :disabled="imported || locked"
                :min="100"
                :max="30000"
                :step="100"
                controls-position="right"
              />
            </el-form-item>
            <el-form-item label="内存限制（MB）">
              <el-input-number
                v-model="form.memoryLimitMb"
                :disabled="imported || locked"
                :min="16"
                :max="2048"
                :step="64"
                controls-position="right"
              />
            </el-form-item>
            <el-form-item label="输出限制（KB）">
              <el-input-number
                v-model="form.outputLimitKb"
                :disabled="imported || locked"
                :min="1"
                :max="65536"
                :step="256"
                controls-position="right"
              />
            </el-form-item>
          </div>
          <el-form-item label="允许语言">
            <el-checkbox-group v-model="form.languages" :disabled="imported || locked">
              <el-checkbox v-for="(label, value) in programmingLanguageLabels" :key="value" :value="value">
                {{ label }}
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>
        </section>

        <section class="block">
          <div class="block-head">
            <h3>公开样例</h3>
            <el-button
              size="small"
              :icon="Plus"
              :disabled="imported || locked || form.samples.length >= 50"
              @click="addPair(form.samples, 50)"
            >
              添加样例
            </el-button>
          </div>
          <div v-for="(sample, index) in form.samples" :key="index" class="pair-row">
            <div class="pair-field">
              <span>样例输入 {{ index + 1 }}</span>
              <el-input v-model="sample.input" type="textarea" :rows="4" :disabled="imported || locked" />
            </div>
            <div class="pair-field">
              <span>样例输出 {{ index + 1 }}</span>
              <el-input v-model="sample.output" type="textarea" :rows="4" :disabled="imported || locked" />
            </div>
            <el-button
              v-if="!imported"
              link
              type="danger"
              :icon="Delete"
              class="pair-remove"
              @click="form.samples.splice(index, 1)"
            />
          </div>
        </section>

        <section class="block">
          <div class="block-head">
            <h3>
              测试数据
              <el-tag size="small" :type="testcaseReady ? 'success' : 'info'" effect="plain" class="head-tag">
                {{ testcaseReady ? '已配置' : '未配置' }}
              </el-tag>
            </h3>
            <div class="head-tools">
              <el-button
                v-if="testcaseEditable"
                size="small"
                :icon="Plus"
                :disabled="cases.length >= 500"
                @click="addPair(cases, 500)"
              >
                添加测试点
              </el-button>
            </div>
          </div>

          <div v-for="(testcase, index) in cases" :key="index" class="pair-row">
            <div class="pair-field">
              <span>输入 {{ index + 1 }}</span>
              <el-input v-model="testcase.input" type="textarea" :rows="4" :disabled="!testcaseEditable" />
            </div>
            <div class="pair-field">
              <span>输出 {{ index + 1 }}</span>
              <el-input v-model="testcase.output" type="textarea" :rows="4" :disabled="!testcaseEditable" />
            </div>
            <el-button
              v-if="testcaseEditable"
              link
              type="danger"
              :icon="Delete"
              class="pair-remove"
              :disabled="cases.length === 1"
              @click="cases.splice(index, 1)"
            />
          </div>
        </section>
      </main>
    </template>

    <CourseProblemPackageImportDialog ref="importDialog" :course-id="courseId" @imported="onImported" />
  </div>
</template>

<style scoped>
.editor {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f6f8;
}

.editor-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 24px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.title-input {
  flex: 1;
  max-width: 640px;
}

.title-input :deep(.el-input__wrapper) {
  box-shadow: none;
  background: transparent;
  padding-left: 0;
}

.title-input :deep(.el-input__inner) {
  font-size: 18px;
  font-weight: 600;
}

.header-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}

.save-alert {
  margin: 12px 24px 0;
}

.panel {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  margin: 16px 24px 24px;
  padding: 4px 28px 20px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
}

.block {
  padding: 18px 0;
}

.block + .block {
  border-top: 1px solid var(--el-border-color-lighter);
}

.block h3 {
  margin: 0 0 12px;
  font-size: 15px;
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.block-head h3 {
  margin: 0;
}

.head-tag {
  margin-left: 8px;
  vertical-align: middle;
}

.head-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}

.statement-preview {
  min-height: 200px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.limits {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.limits :deep(.el-select),
.limits :deep(.el-input-number) {
  width: 100%;
}

.pair-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  gap: 12px;
  align-items: end;
  margin-bottom: 12px;
}

.pair-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.pair-field > span {
  color: var(--text-secondary);
  font-size: 12px;
}

.pair-remove {
  margin-bottom: 6px;
}

.editor-loading {
  padding: 48px 0;
  text-align: center;
  color: var(--text-muted);
}
</style>
