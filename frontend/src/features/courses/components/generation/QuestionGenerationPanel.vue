<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
import { Close, UploadFilled } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'
import { GenerateQuestionsRequestMode, type KnowledgeBaseView } from '@/api/generated'
import { streamGenerateQuestions, type DraftQuestion, type QuizTemplate } from '@/features/courses/questionStream'
import CourseQuestionFormFields from '@/features/courses/components/CourseQuestionFormFields.vue'
import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'
import { confirm } from '@/shared/dialogs'
import { questionTypeLabels } from '@/shared/labels'
import { uploadCourseMaterial, validateUploadFile } from '@/features/courses/materialUpload'
import {
  formatQuestionAnswer,
  questionItemFormFrom,
  questionItemPayload,
  type CourseQuestionTypeValue,
  type QuestionItemForm,
} from '@/features/courses/question'

const props = defineProps<{ courseId: number }>()

type Step = 'input' | 'generating' | 'review'
const step = ref<Step>('input')
const errorText = ref('')

const mode = ref<GenerateQuestionsRequestMode>(GenerateQuestionsRequestMode.CUSTOM)
const requirement = ref('')
const difficulty = ref<'easy' | 'medium' | 'hard'>('medium')
const counts = ref<Record<CourseQuestionTypeValue, number>>({
  single_choice: 3,
  fill_in_blank: 0,
  true_false: 2,
})
const MAX_TOTAL = 20

const totalCount = computed(() => Object.values(counts.value).reduce((sum, count) => sum + count, 0))
const countsError = computed(() => {
  if (totalCount.value === 0) return '请至少为一种题型设置数量'
  if (totalCount.value > MAX_TOTAL) return `单次生成总数不能超过 ${MAX_TOTAL} 道`
  return ''
})

interface UploadedFile {
  materialId: number
  name: string
}
const MAX_ATTACHMENTS = 5
const attachments = ref<UploadedFile[]>([])
const examPaper = ref<UploadedFile>()
const maxQuestions = ref(10)
const uploading = ref(false)
const attachmentUploadRef = ref<UploadInstance>()
const examUploadRef = ref<UploadInstance>()

async function uploadTo(uploadFile: UploadFile, target: 'attachment' | 'exam'): Promise<void> {
  const file = uploadFile.raw
  const uploadRef = target === 'attachment' ? attachmentUploadRef : examUploadRef
  if (!file) return
  const invalid = validateUploadFile(file)
  if (invalid) {
    uploadRef.value?.clearFiles()
    ElMessage.error(invalid)
    return
  }
  if (target === 'exam' && !file.name.toLowerCase().endsWith('.pdf')) {
    uploadRef.value?.clearFiles()
    ElMessage.error('试卷仿写仅支持 PDF 试卷')
    return
  }
  if (target === 'attachment' && attachments.value.length >= MAX_ATTACHMENTS) {
    uploadRef.value?.clearFiles()
    ElMessage.error(`一次最多附加 ${MAX_ATTACHMENTS} 个文件`)
    return
  }
  uploading.value = true
  try {
    const { materialId, name } = await uploadCourseMaterial(props.courseId, file, null)
    const uploaded = { materialId, name: file.name }
    if (target === 'attachment') {
      attachments.value.push(uploaded)
    } else {
      examPaper.value = uploaded
    }
    ElMessage.success(`“${name}”已上传到资料库`)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    uploading.value = false
    uploadRef.value?.clearFiles()
  }
}

function removeAttachment(materialId: number): void {
  attachments.value = attachments.value.filter((file) => file.materialId !== materialId)
}

const knowledgeBases = ref<KnowledgeBaseView[]>([])
const knowledgeBasesLoaded = ref(false)
const selectedKbIds = ref<number[]>([])
const indexStatusLabels: Record<string, string> = {
  empty: '未入库',
  ready: '可检索',
  needs_rebuild: '需重建索引',
}

async function loadKnowledgeBases(): Promise<void> {
  try {
    knowledgeBases.value = (await api.knowledgeBaseAdminList(props.courseId)).data
    knowledgeBasesLoaded.value = true
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

type Phase = 'parsing' | 'exploring' | 'planning' | 'quizzing'
const phase = ref<Phase>('exploring')
const progressLines = ref<string[]>([])
const exploreText = ref('')
interface ToolCall {
  name: string
  summary: string
  done: boolean
}
const toolCalls = ref<ToolCall[]>([])
const plan = ref<{ analysis: string; templates: QuizTemplate[] }>()
const notices = ref<string[]>([])
const questionTotal = ref(0)
let generateAbort: AbortController | null = null

interface DraftRow {
  draft: DraftQuestion
  selected: boolean
  editing: boolean
  form: QuestionItemForm | null
  editError: string
  saveError: string
  saved: boolean
}
const rows = ref<DraftRow[]>([])
const hasUnsaved = computed(() => rows.value.some((row) => !row.saved))

const liveSteps = computed<{ phase: Phase; title: string }[]>(() => {
  const quiz = {
    phase: 'quizzing' as const,
    title: questionTotal.value ? `逐题生成 ${rows.value.length}/${questionTotal.value}` : '逐题生成',
  }
  if (mode.value === GenerateQuestionsRequestMode.MIMIC) {
    return [{ phase: 'parsing', title: '解析试卷并抽题' }, quiz]
  }
  return [
    ...(attachments.value.length ? [{ phase: 'parsing' as const, title: '解析附件' }] : []),
    { phase: 'exploring', title: '探索资料' },
    { phase: 'planning', title: '规划题目' },
    quiz,
  ]
})
const phaseIndex = computed(() => liveSteps.value.findIndex((item) => item.phase === phase.value))

function resetLive(): void {
  phase.value =
    mode.value === GenerateQuestionsRequestMode.MIMIC || attachments.value.length ? 'parsing' : 'exploring'
  progressLines.value = []
  exploreText.value = ''
  toolCalls.value = []
  plan.value = undefined
  notices.value = []
  questionTotal.value = 0
  rows.value = []
}

const isMimic = computed(() => mode.value === GenerateQuestionsRequestMode.MIMIC)

async function start(): Promise<void> {
  if (isMimic.value) {
    if (!examPaper.value) {
      errorText.value = '请先上传试卷 PDF'
      return
    }
  } else {
    if (!requirement.value.trim()) {
      errorText.value = '请填写出题要求'
      return
    }
    if (countsError.value) {
      errorText.value = countsError.value
      return
    }
  }
  errorText.value = ''
  resetLive()
  step.value = 'generating'
  generateAbort = new AbortController()
  const knowledgeBaseIds = selectedKbIds.value.length ? selectedKbIds.value : null
  try {
    await streamGenerateQuestions(
      props.courseId,
      isMimic.value
        ? {
            mode: GenerateQuestionsRequestMode.MIMIC,
            examMaterialId: examPaper.value?.materialId ?? null,
            maxQuestions: maxQuestions.value,
            knowledgeBaseIds,
            requirement: null,
            attachmentMaterialIds: null,
            singleChoiceCount: null,
            fillInBlankCount: null,
            trueFalseCount: null,
            difficulty: null,
          }
        : {
            mode: GenerateQuestionsRequestMode.CUSTOM,
            requirement: requirement.value.trim(),
            attachmentMaterialIds: attachments.value.length
              ? attachments.value.map((file) => file.materialId)
              : null,
            knowledgeBaseIds,
            singleChoiceCount: counts.value.single_choice,
            fillInBlankCount: counts.value.fill_in_blank,
            trueFalseCount: counts.value.true_false,
            difficulty: difficulty.value,
            examMaterialId: null,
            maxQuestions: null,
          },
      (event) => {
        if (event.type === 'stage') {
          phase.value = event.phase
        } else if (event.type === 'progress') {
          progressLines.value.push(event.message)
        } else if (event.type === 'explore_text') {
          exploreText.value += event.text
        } else if (event.type === 'tool') {
          if (event.phase === 'start') {
            toolCalls.value.push({ name: event.name, summary: '检索中…', done: false })
          } else {
            const last = toolCalls.value.findLast((call) => !call.done)
            if (last) {
              last.summary = event.summary
              last.done = true
            }
          }
        } else if (event.type === 'notice') {
          notices.value.push(event.message)
        } else if (event.type === 'plan') {
          plan.value = { analysis: event.analysis, templates: event.templates }
        } else if (event.type === 'question') {
          questionTotal.value = event.total
          rows.value.push({
            draft: event.draft,
            selected: event.draft.issues.length === 0,
            editing: false,
            form: null,
            editError: '',
            saveError: '',
            saved: false,
          })
        } else if (event.type === 'heartbeat') {
          // 思考心跳:仅用于保持连接
        } else if (event.type === 'done') {
          step.value = 'review'
        } else {
          throw new Error(event.message)
        }
      },
      generateAbort.signal,
    )
    // 流正常结束但没收到 done(连接被服务端异常关闭):有草稿就进审阅,没有就报错
    if (step.value === 'generating') {
      finishInterrupted('生成流意外结束')
    }
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      finishInterrupted('已中止生成')
    } else {
      finishInterrupted(errorMessage(error))
    }
  } finally {
    generateAbort = null
  }
}

function finishInterrupted(message: string): void {
  if (rows.value.length > 0) {
    step.value = 'review'
    errorText.value = `${message};已生成的 ${rows.value.length} 道题可先审阅保存`
  } else {
    step.value = 'input'
    errorText.value = message
  }
}

function cancelGenerate(): void {
  generateAbort?.abort()
}

function toggleEdit(row: DraftRow): void {
  if (row.editing) {
    row.editing = false
    row.form = null
    row.editError = ''
    return
  }
  row.form = questionItemFormFrom(row.draft)
  row.editError = ''
  row.editing = true
}

function confirmEdit(row: DraftRow): void {
  if (!row.form) return
  try {
    const payload = questionItemPayload(row.form)
    // 编辑通过表单校验即视为问题已修正,清空问题标记
    row.draft = {
      title: row.draft.title,
      type: payload.type,
      stemMarkdown: payload.stemMarkdown,
      options: payload.options,
      answer: payload.answer,
      analysisMarkdown: payload.analysisMarkdown,
      issues: [],
    }
    row.editing = false
    row.form = null
    row.editError = ''
    row.saveError = ''
  } catch (error: unknown) {
    row.editError = errorMessage(error)
  }
}

const selectedCount = computed(() => rows.value.filter((row) => row.selected && !row.saved).length)
const router = useRouter()

async function saveAsPaper(): Promise<void> {
  const pending = rows.value.filter((row) => row.selected && !row.saved)
  if (pending.length === 0) {
    ElMessage.warning('请先勾选要保存的题目')
    return
  }
  const flawed = pending.filter((row) => row.draft.issues.length > 0 || row.draft.answer === null)
  if (flawed.length > 0) {
    flawed.forEach((row) => {
      row.saveError = '该题仍有问题标记,请先「编辑」修正后再保存'
    })
    ElMessage.warning(`${flawed.length} 道题仍有问题标记,请先修正`)
    return
  }
  pending.forEach((row) => {
    row.saved = true
  })
  await router.push({
    path: `/focus/admin/courses/${props.courseId}/questions/new`,
    state: {
      draftTitle: requirement.value.trim().slice(0, 60),
      draftItems: pending.map((row) => ({
        type: row.draft.type,
        stemMarkdown: row.draft.stemMarkdown,
        options: row.draft.options,
        answer: row.draft.answer,
        analysisMarkdown: row.draft.analysisMarkdown,
      })),
    },
  })
}

async function restart(): Promise<void> {
  if (hasUnsaved.value && !(await confirm('草稿未保存，离开后将丢失。确定重新开始吗？', '未保存的草稿'))) {
    return
  }
  resetLive()
  errorText.value = ''
  step.value = 'input'
}

/** 带问题标记的草稿答案可能是任意形态,格式化失败时退回原样 JSON 展示 */
function draftAnswerText(draft: DraftQuestion): string {
  try {
    return formatQuestionAnswer(draft.type, draft.options, draft.answer)
  } catch {
    return JSON.stringify(draft.answer)
  }
}

watch(
  () => props.courseId,
  () => {
    generateAbort?.abort()
    step.value = 'input'
    resetLive()
    errorText.value = ''
    requirement.value = ''
    attachments.value = []
    examPaper.value = undefined
    selectedKbIds.value = []
    knowledgeBasesLoaded.value = false
    knowledgeBases.value = []
  },
)
</script>

<template>
  <section>
    <div v-if="step === 'input'" class="panel-block">
      <el-alert v-if="errorText" type="error" :title="errorText" :closable="false" class="block-alert" />
      <el-radio-group v-model="mode" class="mode-switch">
        <el-radio-button :value="GenerateQuestionsRequestMode.CUSTOM">自定义出题</el-radio-button>
        <el-radio-button :value="GenerateQuestionsRequestMode.MIMIC">试卷仿写</el-radio-button>
      </el-radio-group>

      <template v-if="!isMimic">
        <h3>出题要求</h3>
        <el-input
          v-model="requirement"
          type="textarea"
          :rows="4"
          maxlength="4000"
          show-word-limit
          placeholder="请输入出题要求"
        />

        <div class="row upload-row">
          <span>附件素材(可选):</span>
          <el-upload
            ref="attachmentUploadRef"
            :auto-upload="false"
            :show-file-list="false"
            :disabled="uploading || attachments.length >= MAX_ATTACHMENTS"
            accept=".pdf,.docx,.pptx,.xlsx,.md,.txt"
            :on-change="(file: UploadFile) => uploadTo(file, 'attachment')"
          >
            <el-button
              :icon="UploadFilled"
              :loading="uploading"
              :disabled="attachments.length >= MAX_ATTACHMENTS"
            >
              上传文件
            </el-button>
          </el-upload>
          <span class="hint inline-hint"
            >最多 {{ MAX_ATTACHMENTS }} 个;pdf / docx / pptx / xlsx / md / txt</span
          >
        </div>
        <ul v-if="attachments.length" class="file-list">
          <li v-for="file in attachments" :key="file.materialId">
            <span>{{ file.name }}</span>
            <el-button link :icon="Close" @click="removeAttachment(file.materialId)" />
          </li>
        </ul>
      </template>

      <template v-else>
        <h3>上传试卷</h3>
        <div class="row upload-row">
          <el-upload
            ref="examUploadRef"
            :auto-upload="false"
            :show-file-list="false"
            :disabled="uploading"
            accept=".pdf"
            :on-change="(file: UploadFile) => uploadTo(file, 'exam')"
          >
            <el-button :icon="UploadFilled" :loading="uploading">
              {{ examPaper ? '更换试卷' : '上传试卷 PDF' }}
            </el-button>
          </el-upload>
          <span v-if="examPaper" class="file-name">{{ examPaper.name }}</span>
        </div>
        <div class="row">
          <span>最多仿写:</span>
          <el-input-number v-model="maxQuestions" :min="1" :max="MAX_TOTAL" />
          <span class="total-note">道</span>
        </div>
        <p class="hint">仅支持单选、填空、判断题。</p>
      </template>

      <div class="row kb-row">
        <span>知识库(可选):</span>
        <el-select
          v-model="selectedKbIds"
          multiple
          collapse-tags
          collapse-tags-tooltip
          clearable
          placeholder="选择知识库"
          class="kb-select"
          @visible-change="(open: boolean) => open && !knowledgeBasesLoaded && loadKnowledgeBases()"
        >
          <el-option
            v-for="kb in knowledgeBases"
            :key="kb.id"
            :value="kb.id"
            :label="kb.name"
            :disabled="kb.indexStatus !== 'ready'"
          >
            <div class="kb-option">
              <span>{{ kb.name }}</span>
              <span class="kb-meta">
                {{ kb.documentCount }} 篇文档 ·
                <el-tag size="small" :type="kb.indexStatus === 'ready' ? 'success' : 'info'" effect="plain">
                  {{ indexStatusLabels[kb.indexStatus] ?? kb.indexStatus }}
                </el-tag>
              </span>
            </div>
          </el-option>
        </el-select>
      </div>
      <p v-if="knowledgeBasesLoaded && knowledgeBases.length === 0" class="hint">本课程还没有知识库。</p>

      <template v-if="!isMimic">
        <div class="row">
          <span>单选题:</span>
          <el-input-number v-model="counts.single_choice" :min="0" :max="10" />
          <span>填空题:</span>
          <el-input-number v-model="counts.fill_in_blank" :min="0" :max="10" />
          <span>判断题:</span>
          <el-input-number v-model="counts.true_false" :min="0" :max="10" />
          <span class="total-note"
            >共 <b>{{ totalCount }}</b> 道</span
          >
        </div>
        <div class="row">
          <span>难度:</span>
          <el-radio-group v-model="difficulty">
            <el-radio value="easy">简单</el-radio>
            <el-radio value="medium">中等</el-radio>
            <el-radio value="hard">困难</el-radio>
          </el-radio-group>
        </div>
      </template>
      <div class="row">
        <el-button type="primary" :disabled="uploading || (!isMimic && !!countsError)" @click="start">
          {{ isMimic ? '开始仿写' : '生成试题' }}
        </el-button>
        <span v-if="!isMimic && countsError" class="counts-error">{{ countsError }}</span>
      </div>
    </div>

    <div v-else-if="step === 'generating'" class="panel-block">
      <div class="live-head">
        <el-steps :active="phaseIndex" finish-status="success" simple class="live-steps">
          <el-step v-for="item in liveSteps" :key="item.phase" :title="item.title" />
        </el-steps>
        <el-button @click="cancelGenerate">中止</el-button>
      </div>

      <el-alert
        v-for="(notice, i) in notices"
        :key="i"
        type="warning"
        :title="notice"
        :closable="false"
        class="block-alert"
      />

      <section v-if="progressLines.length" class="live-block">
        <h4>{{ isMimic ? '解析试卷' : '解析附件' }}</h4>
        <ul class="progress-list">
          <li v-for="(line, i) in progressLines" :key="i">{{ line }}</li>
        </ul>
      </section>

      <section v-if="!isMimic" class="live-block">
        <h4>探索</h4>
        <p v-if="exploreText" class="explore-text">{{ exploreText }}</p>
        <p v-else class="hint">{{ phase === 'exploring' ? 'AI 正在分析出题要求…' : '(本轮没有探索文字)' }}</p>
        <div v-if="toolCalls.length" class="tool-list">
          <div v-for="(call, i) in toolCalls" :key="i" class="tool-card">
            <el-tag size="small" :type="call.done ? 'success' : 'primary'" effect="plain">检索</el-tag>
            <span>{{ call.summary }}</span>
          </div>
        </div>
      </section>

      <section v-if="plan" class="live-block">
        <h4>{{ isMimic ? '从试卷抽出的题目' : '规划' }}</h4>
        <p v-if="plan.analysis" class="hint">{{ plan.analysis }}</p>
        <ol class="plan-list">
          <li v-for="template in plan.templates" :key="template.questionId">
            <el-tag size="small">{{ questionTypeLabels[template.type] }}</el-tag>
            <span>{{ template.topic }}</span>
          </li>
        </ol>
      </section>

      <section v-if="rows.length" class="live-block">
        <h4>已生成 {{ rows.length }} 道</h4>
        <ul class="arrived-list">
          <li v-for="(row, index) in rows" :key="index">
            <el-tag size="small">{{ questionTypeLabels[row.draft.type] }}</el-tag>
            <span>{{ row.draft.title }}</span>
            <el-tag v-if="row.draft.issues.length" size="small" type="warning">待修正</el-tag>
          </li>
        </ul>
      </section>
    </div>

    <div v-else class="panel-block">
      <el-alert v-if="errorText" type="warning" :title="errorText" :closable="false" class="block-alert" />
      <el-alert
        v-for="(notice, i) in notices"
        :key="i"
        type="warning"
        :title="notice"
        :closable="false"
        class="block-alert"
      />
      <div v-if="exploreText" class="review-preface">
        <span class="inspector-label">探索前言</span>
        <p>{{ exploreText }}</p>
      </div>
      <div class="review-header">
        <h3>审阅草稿({{ rows.length }} 道)</h3>
        <div>
          <el-button @click="restart">重新开始</el-button>
          <el-button type="primary" :disabled="selectedCount === 0" @click="saveAsPaper">
            将勾选的 {{ selectedCount }} 道题保存为试题
          </el-button>
        </div>
      </div>

      <div v-for="(row, index) in rows" :key="index" class="draft-card">
        <div class="draft-head">
          <el-checkbox v-model="row.selected" :disabled="row.saved" />
          <el-tag size="small">{{ questionTypeLabels[row.draft.type] }}</el-tag>
          <b class="draft-title">{{ row.draft.title }}</b>
          <el-tag v-if="row.draft.issues.length" size="small" type="warning">
            {{ row.draft.issues.length }} 处问题待修正
          </el-tag>
          <el-tag v-if="row.saved" size="small" type="success">已保存</el-tag>
          <el-button link type="primary" :disabled="row.saved" @click="toggleEdit(row)">
            {{ row.editing ? '收起编辑' : '编辑' }}
          </el-button>
        </div>

        <template v-if="!row.editing">
          <el-alert v-if="row.draft.issues.length" type="warning" :closable="false" class="draft-issues">
            <p v-for="(issue, i) in row.draft.issues" :key="i" class="draft-issue-line">{{ issue }}</p>
          </el-alert>
          <MarkdownRenderer class="draft-stem" :source="row.draft.stemMarkdown" />
          <ol v-if="Array.isArray(row.draft.options)" class="draft-options" type="A">
            <li v-for="(option, i) in row.draft.options" :key="i">
              <MarkdownRenderer inline :source="option" />
            </li>
          </ol>
          <p class="draft-answer">答案:<MarkdownRenderer inline :source="draftAnswerText(row.draft)" /></p>
          <p v-if="row.draft.analysisMarkdown" class="draft-analysis">
            解析:<MarkdownRenderer inline :source="row.draft.analysisMarkdown" />
          </p>
        </template>
        <div v-else class="draft-edit">
          <el-alert
            v-if="row.editError"
            type="error"
            :title="row.editError"
            :closable="false"
            class="block-alert"
          />
          <CourseQuestionFormFields v-if="row.form" v-model="row.form" />
          <div class="row">
            <el-button @click="toggleEdit(row)">取消</el-button>
            <el-button type="primary" @click="confirmEdit(row)">应用修改</el-button>
          </div>
        </div>

        <el-alert
          v-if="row.saveError"
          type="error"
          :title="`保存失败:${row.saveError}`"
          :closable="false"
          class="block-alert"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.panel-block h3 {
  margin: 0 0 12px;
}

.block-alert {
  margin-bottom: 12px;
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  flex-wrap: wrap;
}

.kb-select {
  min-width: 420px;
}

.kb-option {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.kb-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.hint {
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.total-note {
  color: var(--el-text-color-secondary);
}

.mode-switch {
  margin-bottom: 14px;
}

.inline-hint {
  margin-top: 0;
}

.file-list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}

.file-list li {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
}

.file-name {
  font-size: 13px;
}

.progress-list {
  margin: 0;
  padding-left: 18px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.counts-error {
  color: var(--el-color-danger);
  font-size: 13px;
}

.live-head {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}

.live-steps {
  flex: 1;
}

.live-block {
  margin-bottom: 18px;
}

.live-block h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.explore-text {
  margin: 0 0 10px;
  white-space: pre-wrap;
  line-height: 1.7;
}

.tool-list {
  display: grid;
  gap: 6px;
}

.tool-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  font-size: 13px;
}

.plan-list,
.arrived-list {
  margin: 0;
  padding-left: 22px;
  display: grid;
  gap: 6px;
  font-size: 13px;
}

.arrived-list {
  list-style: none;
  padding-left: 0;
}

.plan-list li,
.arrived-list li {
  display: flex;
  align-items: center;
  gap: 8px;
}

.review-preface {
  margin-bottom: 14px;
  padding: 10px 14px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
}

.review-preface p {
  margin: 6px 0 0;
  white-space: pre-wrap;
  line-height: 1.7;
  font-size: 13px;
}

.inspector-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.review-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.review-header h3 {
  margin: 0;
}

.draft-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 14px;
  margin-bottom: 12px;
}

.draft-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.draft-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.draft-stem {
  margin: 10px 0 6px;
  white-space: pre-wrap;
}

.draft-options {
  margin: 0 0 6px;
  padding-left: 28px;
}

.draft-answer {
  margin: 0 0 4px;
  color: var(--el-color-success);
}

.draft-analysis {
  margin: 0;
  color: var(--el-text-color-secondary);
  white-space: pre-wrap;
}

.draft-edit {
  margin-top: 12px;
}

.draft-issues {
  margin-top: 10px;
}

.draft-issue-line {
  margin: 0;
}
</style>
