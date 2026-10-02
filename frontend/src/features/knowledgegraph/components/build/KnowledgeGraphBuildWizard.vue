<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { BuildDetailView, BuildPreview } from '@/api/generated'
import { streamBuildEvents } from '@/features/knowledgegraph/kgBuildStream'
import GraphMindMap from '@/features/knowledgegraph/components/GraphMindMap.vue'
import TocEditor, { type TocRow } from '@/features/knowledgegraph/components/build/TocEditor.vue'
import { previewItems } from '@/features/knowledgegraph/knowledgeGraph'
import { uploadCourseMaterial, validateUploadFile } from '@/features/courses/materialUpload'

const props = defineProps<{ courseId: number; buildId: number | undefined }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ completed: [graphId: number]; changed: [] }>()

type Step = 'material' | 'parsing' | 'toc' | 'extracting' | 'preview'
const step = ref<Step>('material')
const stepIndex = computed(() => ['material', 'parsing', 'toc', 'extracting', 'preview'].indexOf(step.value))

const detail = ref<BuildDetailView>()
const errorText = ref('')
let eventsAbort: AbortController | null = null

const selectedMaterialId = ref<number>()
const uploadedFileName = ref('')
const creating = ref(false)
const uploadRef = ref<UploadInstance>()
const uploading = ref(false)

async function uploadAndSelect(uploadFile: UploadFile): Promise<void> {
  const file = uploadFile.raw
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    uploadRef.value?.clearFiles()
    ElMessage.error('请选择 PDF 文件')
    return
  }
  const invalid = validateUploadFile(file)
  if (invalid) {
    uploadRef.value?.clearFiles()
    ElMessage.error(invalid)
    return
  }
  uploading.value = true
  try {
    selectedMaterialId.value = (await uploadCourseMaterial(props.courseId, file, null)).materialId
    uploadedFileName.value = file.name
    ElMessage.success(`「${file.name}」已上传,可开始解析`)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    uploading.value = false
    uploadRef.value?.clearFiles()
  }
}

async function createBuild(): Promise<void> {
  if (!selectedMaterialId.value) {
    ElMessage.warning('请先选择教材 PDF')
    return
  }
  creating.value = true
  try {
    const response = await api.knowledgeGraphBuildCreate(props.courseId, {
      materialId: selectedMaterialId.value,
    })
    emit('changed')
    await openBuild(response.data.id)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    creating.value = false
  }
}

const stageLabel = ref('')
const parseMessages = ref<string[]>([])
const extractProgress = ref<{ current: number; total: number; sectionTitle: string }>()
/**
 * 进度流断开(代理超时、网络抖动、服务端超时)不打扰教师:任务在后台照跑,
 * attach 端点重连时会重放已发生的事件,所以静默重连即可;任务已到静止状态则不再连。
 */
function attachEvents(buildId: number): void {
  eventsAbort?.abort()
  const abort = new AbortController()
  eventsAbort = abort
  const connect = (): void => {
    streamBuildEvents(
      props.courseId,
      buildId,
      (event) => {
        if (event.type === 'stage') {
          stageLabel.value = event.label
        } else if (event.type === 'parse_progress') {
          parseMessages.value = [...parseMessages.value.slice(-6), event.message]
        } else if (event.type === 'toc_ready') {
          void refreshDetail(buildId)
        } else if (event.type === 'progress') {
          extractProgress.value = event
        } else if (event.type === 'extracted') {
          void refreshDetail(buildId)
        } else if (event.type === 'status') {
          void refreshDetail(buildId)
        } else if (event.type === 'error') {
          errorText.value = event.message
          stageLabel.value = ''
          parseMessages.value = []
          void refreshDetail(buildId)
        }
      },
      abort.signal,
    )
      .catch(() => undefined)
      .then(async () => {
        if (abort.signal.aborted) return
        try {
          await refreshDetail(buildId)
        } catch {
          // 详情也拿不到(如后端暂不可达):按在途处理,继续按周期重连
        }
        if (abort.signal.aborted) return
        const status = detail.value?.build.status
        if (status === undefined || status === 'parsing' || status === 'extracting') {
          window.setTimeout(() => {
            if (!abort.signal.aborted) connect()
          }, 2000)
        }
      })
  }
  connect()
}

const tocRows = ref<TocRow[]>([])
const savingToc = ref(false)
const startingExtract = ref(false)

async function saveToc(startAfter: boolean): Promise<void> {
  if (!detail.value) return
  savingToc.value = true
  errorText.value = ''
  try {
    const response = await api.knowledgeGraphBuildSaveToc(props.courseId, detail.value.build.id, {
      entries: tocRows.value,
    })
    detail.value = response.data
    if (!startAfter) {
      ElMessage.success('目录已保存')
      return
    }
    startingExtract.value = true
    await api.knowledgeGraphBuildExtract(props.courseId, detail.value.build.id)
    emit('changed')
    extractProgress.value = undefined
    step.value = 'extracting'
    attachEvents(detail.value.build.id)
  } catch (error: unknown) {
    errorText.value = errorMessage(error)
  } finally {
    savingToc.value = false
    startingExtract.value = false
  }
}

async function retryExtract(): Promise<void> {
  if (!detail.value) return
  errorText.value = ''
  try {
    await api.knowledgeGraphBuildExtract(props.courseId, detail.value.build.id)
    step.value = 'extracting'
    attachEvents(detail.value.build.id)
  } catch (error: unknown) {
    errorText.value = errorMessage(error)
  }
}

const canIgnoreFailed = computed(() => {
  if (detail.value?.build.status !== 'failed') return false
  const sections = detail.value.sections
  return (
    sections.some((section) => section.status === 'done') &&
    sections.some((section) => section.status === 'failed') &&
    !sections.some((section) => section.status === 'pending' || section.status === 'running')
  )
})

async function ignoreFailedAndMerge(): Promise<void> {
  if (!detail.value) return
  errorText.value = ''
  try {
    await api.knowledgeGraphBuildMerge(props.courseId, detail.value.build.id)
    step.value = 'extracting'
    attachEvents(detail.value.build.id)
  } catch (error: unknown) {
    errorText.value = errorMessage(error)
  }
}

async function retryParse(): Promise<void> {
  if (!detail.value) return
  errorText.value = ''
  try {
    await api.knowledgeGraphBuildRetryParse(props.courseId, detail.value.build.id)
    parseMessages.value = []
    step.value = 'parsing'
    attachEvents(detail.value.build.id)
  } catch (error: unknown) {
    errorText.value = errorMessage(error)
  }
}

async function cancelRun(): Promise<void> {
  if (!detail.value) return
  try {
    await api.knowledgeGraphBuildCancel(props.courseId, detail.value.build.id)
    ElMessage.info('已请求中止,当前小节完成后停止')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

const preview = ref<BuildPreview>({ nodes: [], edges: [], warnings: [] })
const previewMindMap = computed(() => previewItems(preview.value.nodes))
const graphName = ref('')
const completing = ref(false)

async function loadPreview(buildId: number): Promise<void> {
  try {
    preview.value = (await api.knowledgeGraphBuildPreview(props.courseId, buildId)).data
  } catch (error: unknown) {
    errorText.value = errorMessage(error)
  }
}

async function complete(): Promise<void> {
  if (!detail.value) return
  if (!graphName.value.trim()) {
    ElMessage.warning('请输入图谱名称')
    return
  }
  completing.value = true
  try {
    const response = await api.knowledgeGraphBuildComplete(props.courseId, detail.value.build.id, {
      name: graphName.value.trim(),
    })
    ElMessage.success(`知识图谱「${response.data.name}」已入库，确认内容后可发布给学生`)
    emit('completed', response.data.id)
    emit('changed')
    visible.value = false
  } catch (error: unknown) {
    errorText.value = errorMessage(error)
  } finally {
    completing.value = false
  }
}

async function refreshDetail(buildId: number): Promise<void> {
  const response = await api.knowledgeGraphBuildGet(props.courseId, buildId)
  detail.value = response.data
  const status = response.data.build.status
  if (status === 'parsing') {
    step.value = 'parsing'
  } else if (status === 'toc_ready') {
    step.value = 'toc'
    const source = response.data.tocConfirmed.length ? response.data.tocConfirmed : response.data.tocDraft
    tocRows.value = source.map((entry) => ({ ...entry }))
  } else if (status === 'extracting') {
    step.value = 'extracting'
  } else if (status === 'extracted') {
    step.value = 'preview'
    graphName.value ||= `${response.data.build.materialName.replace(/\.pdf$/i, '')}知识图谱`
    await loadPreview(buildId)
  } else if (status === 'failed') {
    errorText.value = response.data.build.errorMessage ?? '构建失败'
    if (response.data.tocDraft.length === 0) {
      step.value = 'parsing'
    } else {
      const source = response.data.tocConfirmed.length ? response.data.tocConfirmed : response.data.tocDraft
      tocRows.value = source.map((entry) => ({ ...entry }))
      // 抽取过(已有小节记录)的失败停在抽取面板:重试 / 忽略失败小节 / 返回修改目录
      step.value = response.data.sections.length > 0 ? 'extracting' : 'toc'
    }
  } else {
    step.value = 'preview'
    await loadPreview(buildId)
  }
}

async function openBuild(buildId: number): Promise<void> {
  errorText.value = ''
  parseMessages.value = []
  await refreshDetail(buildId)
  const status = detail.value?.build.status
  if (status === 'parsing' || status === 'extracting') {
    attachEvents(buildId)
  }
}

watch(visible, (open) => {
  if (!open) {
    eventsAbort?.abort()
    return
  }
  detail.value = undefined
  errorText.value = ''
  step.value = 'material'
  selectedMaterialId.value = undefined
  uploadedFileName.value = ''
  if (props.buildId) {
    void openBuild(props.buildId)
  }
})

async function confirmClose(): Promise<void> {
  const status = detail.value?.build.status
  if (status === 'parsing' || status === 'extracting') {
    if (!(await confirm('任务会继续在后台执行,可稍后从「构建记录」继续查看。确定关闭吗?', '关闭向导'))) return
  }
  visible.value = false
}

onBeforeUnmount(() => eventsAbort?.abort())
</script>

<template>
  <el-dialog
    v-model="visible"
    title="从教材生成知识图谱"
    width="1120px"
    top="4vh"
    :close-on-click-modal="false"
    :before-close="confirmClose"
    destroy-on-close
  >
    <el-steps :active="stepIndex" finish-status="success" class="wizard-steps">
      <el-step title="选择教材" />
      <el-step title="解析与目录识别" />
      <el-step title="目录确认" />
      <el-step title="抽取知识点" />
      <el-step title="预览入库" />
    </el-steps>

    <el-alert v-if="errorText" type="error" :title="errorText" :closable="false" class="wizard-alert" />

    <section v-if="step === 'material'" class="wizard-panel">
      <div class="material-row">
        <el-upload
          ref="uploadRef"
          :auto-upload="false"
          :limit="1"
          :show-file-list="false"
          accept=".pdf,application/pdf"
          :on-change="uploadAndSelect"
        >
          <el-button type="primary" plain :loading="uploading" :icon="UploadFilled">
            {{ uploading ? '正在上传…' : uploadedFileName ? '重新上传' : '上传教材 PDF' }}
          </el-button>
        </el-upload>
        <el-tag v-if="uploadedFileName" type="success" effect="plain">{{ uploadedFileName }}</el-tag>
      </div>
      <div class="actions">
        <el-button type="primary" :loading="creating" :disabled="!selectedMaterialId" @click="createBuild">
          开始解析
        </el-button>
      </div>
    </section>

    <section v-else-if="step === 'parsing'" class="wizard-panel">
      <template v-if="detail?.build.status !== 'failed'">
        <p class="stage-label">{{ stageLabel || '正在解析教材…' }}</p>
        <ul class="message-list">
          <li v-for="(message, index) in parseMessages" :key="index">{{ message }}</li>
        </ul>
      </template>
      <div class="actions">
        <el-button v-if="detail?.build.status === 'parsing'" @click="cancelRun">中止</el-button>
        <el-button v-if="detail?.build.status === 'failed'" type="primary" @click="retryParse">
          重新解析
        </el-button>
      </div>
    </section>

    <section v-else-if="step === 'toc' && detail" class="wizard-panel">
      <TocEditor
        v-model="tocRows"
        :course-id="courseId"
        :build-id="detail.build.id"
        :degraded="detail.degraded"
        :notes="detail.notes"
        :page-count="detail.pageCount"
      />
      <div class="actions">
        <span class="hint toc-hint">
          共 {{ tocRows.length }} 条,全书 {{ detail.pageCount }} 页
        </span>
        <el-button :loading="savingToc" @click="saveToc(false)">仅保存目录</el-button>
        <el-button type="primary" :loading="savingToc || startingExtract" @click="saveToc(true)">
          确认目录,开始抽取
        </el-button>
      </div>
    </section>

    <section v-else-if="step === 'extracting'" class="wizard-panel">
      <p v-if="detail?.build.status !== 'failed'" class="stage-label">{{ stageLabel || '正在抽取…' }}</p>
      <template v-if="extractProgress && detail?.build.status !== 'failed'">
        <el-progress
          :percentage="Math.round((extractProgress.current / Math.max(1, extractProgress.total)) * 100)"
        />
        <p class="hint">
          {{ extractProgress.current }}/{{ extractProgress.total }} · 当前:{{ extractProgress.sectionTitle }}
        </p>
      </template>
      <div class="actions">
        <el-button v-if="detail?.build.status === 'extracting'" @click="cancelRun">中止</el-button>
        <el-button v-if="detail?.build.status === 'failed'" type="primary" @click="retryExtract">
          重试抽取（只跑未完成的小节）
        </el-button>
        <el-button v-if="canIgnoreFailed" @click="ignoreFailedAndMerge">忽略失败小节，直接合并</el-button>
        <el-button v-if="detail?.build.status === 'failed'" @click="step = 'toc'"> 返回修改目录 </el-button>
      </div>
    </section>

    <section v-else-if="step === 'preview'" class="wizard-panel">
      <el-collapse v-if="preview.warnings.length" class="warning-collapse">
        <el-collapse-item :title="`警告 ${preview.warnings.length} 条`">
          <p v-for="warning in preview.warnings" :key="warning" class="warning-item">{{ warning }}</p>
        </el-collapse-item>
      </el-collapse>
      <p class="hint">
        共 {{ preview.nodes.length }} 个节点、{{ preview.edges.length }} 条关系
      </p>
      <div class="preview-canvas">
        <GraphMindMap
          :root-label="detail?.build.materialName.replace(/\.pdf$/i, '') ?? '教材'"
          :items="previewMindMap"
          :selected-id="null"
        />
      </div>
      <div class="complete-form">
        <el-input v-model="graphName" maxlength="128" placeholder="图谱名称" class="name-input" />
        <el-button type="primary" :loading="completing" @click="complete">入库为课程知识图谱</el-button>
      </div>
    </section>
  </el-dialog>
</template>

<style scoped>
.wizard-steps {
  margin-bottom: 18px;
}

.wizard-alert {
  margin-bottom: 14px;
}

.wizard-panel {
  min-height: 320px;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 0 0 12px;
}

.material-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.material-row .hint {
  margin: 0;
}

.actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

.toc-hint {
  margin: 0 auto 0 0;
}

.warning-collapse {
  margin-bottom: 12px;
}

.warning-item {
  margin: 0 0 6px;
  font-size: 13px;
  color: var(--el-color-warning-dark-2, #b88230);
}

.stage-label {
  font-size: 14px;
  margin: 0 0 12px;
}

.message-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.preview-canvas {
  height: 480px;
  margin-bottom: 14px;
}

.complete-form {
  display: flex;
  gap: 10px;
}

.name-input {
  flex: 1;
  max-width: 420px;
}
</style>
