<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { CoursewareMaterialView, SceneOutlinePayload } from '@/api/generated'
import {
  streamGenerateOutline,
  streamGenerateSpeech,
  streamGenerateStage,
  streamParseMaterials,
  type GenerationEvent,
} from '@/features/courseware/coursewareStream'
import type { OutlineImage, OutlineScene, OutlineSceneType } from '@/features/courseware/dsl'
import {
  IMAGE_ASPECT_RATIOS,
  IMAGE_ASPECT_RATIO_LABELS,
  LIMITS,
  PRESET_LABELS,
  PRESET_NAMES,
  WIDGET_TYPES,
  WIDGET_TYPE_LABELS,
} from '@/features/courseware/dsl'
import {
  addScene,
  countScenes,
  deriveTotalScenes,
  moveScene,
  removeLastScene,
  validateOutline,
  withSceneType,
} from '@/features/courseware/outlineEdit'
import { formatFileSize } from '@/shared/format'

const props = defineProps<{
  courseId: number
  coursewareId: number
  stageTitle: string
  sceneCount: number
  speechMissing: number
  unvoicedSegments: number
  synthesizing: boolean
}>()
/** stageChanged:课件被写入(每页落库、清空)→ 宿主重取;generating:生成期间宿主停用手工编辑;synthesize:请宿主合成语音 */
const emit = defineEmits<{ stageChanged: []; generating: [value: boolean]; synthesize: [] }>()

type Step = 'input' | 'outline' | 'generating' | 'finished'
const step = ref<Step>('input')

const MATERIAL_ACCEPT = '.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.png,.jpg,.jpeg,.webp,.txt,.md,.markdown'
const MATERIAL_MAX_FILES = 5
const MATERIAL_MAX_FILE_BYTES = 50 * 1024 * 1024
const MATERIAL_MAX_TOTAL_BYTES = 150 * 1024 * 1024

const materials = ref<CoursewareMaterialView[]>([])
const uploading = ref(false)
const uploadProgress = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

async function loadMaterials(): Promise<void> {
  try {
    const response = await api.coursewareMaterialList(props.courseId, props.coursewareId)
    materials.value = response.data
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

function pickFiles(): void {
  fileInput.value?.click()
}

async function onFilesPicked(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (files.length === 0) return
  if (files.length > MATERIAL_MAX_FILES) {
    ElMessage.warning(`一次最多上传 ${MATERIAL_MAX_FILES} 份文件`)
    return
  }
  const oversize = files.find((f) => f.size > MATERIAL_MAX_FILE_BYTES)
  if (oversize) {
    ElMessage.warning(`「${oversize.name}」超过 ${formatFileSize(MATERIAL_MAX_FILE_BYTES)}`)
    return
  }
  if (files.reduce((sum, f) => sum + f.size, 0) > MATERIAL_MAX_TOTAL_BYTES) {
    ElMessage.warning(`文件总大小不能超过 ${formatFileSize(MATERIAL_MAX_TOTAL_BYTES)}`)
    return
  }
  uploading.value = true
  uploadProgress.value = '上传中…'
  try {
    await streamParseMaterials(props.courseId, props.coursewareId, files, (ev) => {
      if (ev.type === 'progress') uploadProgress.value = ev.message
      else if (ev.type === 'done') {
        if (ev.truncated) {
          ElMessage.warning(`素材文本 ${ev.totalRawChars} 字超出预算,已按公平分配截取 ${ev.chars} 字`)
        }
      } else throw new Error(ev.message)
    })
    await loadMaterials()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    uploading.value = false
    uploadProgress.value = ''
  }
}

async function removeMaterial(item: CoursewareMaterialView): Promise<void> {
  if (!(await confirm(`删除素材「${item.name}」?`, '删除素材'))) return
  try {
    await api.coursewareMaterialDelete(props.courseId, props.coursewareId, item.id)
    await loadMaterials()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

const requirement = ref('')
const contentCount = ref<number | null>(null)
const quizCount = ref<number | null>(null)
const interactiveCount = ref<number | null>(null)

const totalScenes = computed(() => deriveTotalScenes(contentCount.value, quizCount.value, interactiveCount.value))
const countsError = computed(() =>
  totalScenes.value !== null && totalScenes.value > LIMITS.sceneCountMax
    ? `共 ${totalScenes.value} 页,超出上限 ${LIMITS.sceneCountMax} 页,请减少页数`
    : null,
)

const title = ref('')
const outline = ref<OutlineScene[]>([])
const outlineImages = ref<OutlineImage[]>([])
const sceneCounts = computed(() => countScenes(outline.value))
const PRESET_OPTIONS = PRESET_NAMES.filter((name) => name !== 'quiz').map((value) => ({ value, label: PRESET_LABELS[value] }))
const WIDGET_OPTIONS = WIDGET_TYPES.map((value) => ({ value, label: WIDGET_TYPE_LABELS[value] }))

const imageOptions = computed(() =>
  outlineImages.value.flatMap((entry) => {
    const image = materials.value.find((m) => m.id === entry.materialId)?.images.find((i) => i.id === entry.imageId)
    if (!image) return []
    const page = image.pageNumber > 0 ? ` 第 ${image.pageNumber} 页` : ''
    return [{ id: entry.id, url: image.url, label: `${image.sourceDocumentName}${page}${image.description ? ' · ' + image.description : ''}` }]
  }),
)

function adjustScene(type: OutlineSceneType, delta: 1 | -1): void {
  outline.value = delta === 1 ? addScene(outline.value, type) : removeLastScene(outline.value, type)
}

function moveOutlineScene(index: number, direction: -1 | 1): void {
  outline.value = moveScene(outline.value, index, direction)
}

function changeSceneType(index: number, type: OutlineSceneType): void {
  outline.value[index] = withSceneType(outline.value[index]!, type)
}

function removeOutlineScene(index: number): void {
  outline.value.splice(index, 1)
}

interface SceneProgress {
  order: number
  title: string
  status: 'waiting' | 'running' | 'done' | 'failed'
  message: string
  traces: string[]
}
const progress = ref<SceneProgress[]>([])
const outlineTraces = ref<string[]>([])
const running = ref(false)
const errorText = ref('')
const finishedText = ref('')
let abort: AbortController | null = null

function progressOf(order: number): SceneProgress | undefined {
  return progress.value.find((p) => p.order === order)
}

async function generateOutline(): Promise<void> {
  if (requirement.value.trim() === '') {
    ElMessage.warning('请先填写教学需求')
    return
  }
  if (countsError.value) {
    ElMessage.warning(countsError.value)
    return
  }
  errorText.value = ''
  outlineTraces.value = []
  running.value = true
  abort = new AbortController()
  try {
    await streamGenerateOutline(
      props.courseId,
      props.coursewareId,
      {
        requirement: requirement.value.trim(),
        sceneCount: totalScenes.value,
        quizCount: quizCount.value,
        interactiveCount: interactiveCount.value,
      },
      (event) => {
        if (event.type === 'trace') outlineTraces.value.push(event.message)
        else if (event.type === 'outline') {
          title.value = event.title
          outline.value = event.scenes.map((scene) => ({ ...scene, keyPoints: scene.keyPoints ?? [] }))
          outlineImages.value = event.images
          step.value = 'outline'
        } else if (event.type === 'error') throw new Error(event.message)
      },
      abort.signal,
    )
  } catch (error: unknown) {
    if (!(error instanceof DOMException && error.name === 'AbortError')) errorText.value = errorMessage(error)
  } finally {
    running.value = false
    abort = null
  }
}

function toPayload(scene: OutlineScene): SceneOutlinePayload {
  const byId = new Map(outlineImages.value.map((entry) => [entry.id, entry]))
  return {
    title: scene.title.trim(),
    type: scene.type,
    preset: scene.preset,
    summary: scene.summary.trim(),
    keyPoints: scene.keyPoints.map((point) => point.trim()).filter(Boolean),
    widgetType: scene.type === 'interactive' ? (scene.widgetType ?? null) : null,
    widgetOutline: scene.type === 'interactive' ? ((scene.widgetOutline ?? {}) as Record<string, unknown>) : null,
    images:
      scene.type === 'content'
        ? (scene.imageIds ?? []).flatMap((id) => {
            const entry = byId.get(id)
            return entry ? [{ materialId: entry.materialId, imageId: entry.imageId }] : []
          })
        : null,
    illustration:
      scene.type === 'content' && scene.illustration
        ? { prompt: scene.illustration.prompt.trim(), aspectRatio: scene.illustration.aspectRatio }
        : null,
  }
}

async function generateStage(): Promise<void> {
  const errors = validateOutline(outline.value)
  if (errors.length > 0) {
    ElMessage.warning(errors[0])
    return
  }
  if (!title.value.trim()) {
    ElMessage.warning('请填写课件标题')
    return
  }
  if (
    props.sceneCount > 0 &&
    !(await confirm(`课件现有 ${props.sceneCount} 页将被清空,再按这份大纲生成 ${outline.value.length} 页。继续?`, '按大纲生成'))
  ) {
    return
  }
  errorText.value = ''
  finishedText.value = ''
  progress.value = outline.value.map((scene, i) => ({
    order: i + 1,
    title: scene.title,
    status: 'waiting',
    message: '',
    traces: [],
  }))
  step.value = 'generating'
  running.value = true
  emit('generating', true)
  abort = new AbortController()
  try {
    await streamGenerateStage(
      props.courseId,
      props.coursewareId,
      { title: title.value.trim(), scenes: outline.value.map(toPayload) },
      handleGenerationEvent,
      abort.signal,
    )
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      errorText.value = '已取消;已生成的页保留在课件里'
    } else {
      errorText.value = errorMessage(error)
    }
    step.value = 'finished'
  } finally {
    running.value = false
    emit('generating', false)
    emit('stageChanged')
    abort = null
  }
}

function handleGenerationEvent(event: GenerationEvent): void {
  switch (event.type) {
    case 'scene_start': {
      const item = progressOf(event.order)
      if (item) item.status = 'running'
      break
    }
    case 'trace': {
      progressOf(event.order)?.traces.push(event.message)
      break
    }
    case 'scene_done': {
      const item = progressOf(event.order)
      if (item) {
        item.status = 'done'
        item.message = event.warnings.join(';')
      }
      emit('stageChanged')
      break
    }
    case 'scene_failed': {
      const item = progressOf(event.order)
      if (item) {
        item.status = 'failed'
        item.message = event.message
      }
      emit('stageChanged')
      break
    }
    case 'done': {
      const failed = event.failed ?? 0
      finishedText.value =
        failed === 0
          ? `全部 ${event.total ?? progress.value.length} 页已生成`
          : `已生成 ${(event.total ?? progress.value.length) - failed} 页,${failed} 页失败(已保留为占位页,在画布上点「重生成本页」即可重试)`
      step.value = 'finished'
      break
    }
    case 'error':
      throw new Error(event.message)
    default:
      break
  }
}

function cancel(): void {
  abort?.abort()
}

function addKeyPoint(scene: OutlineScene): void {
  if (scene.keyPoints.length >= LIMITS.keyPointsMax) return
  scene.keyPoints.push('')
}

function removeKeyPoint(scene: OutlineScene, index: number): void {
  scene.keyPoints.splice(index, 1)
}

/** 讲解页要一张 AI 画的配图:宽高比跟着预设走(右栏用方图,文字下方用横图) */
function addIllustration(scene: OutlineScene): void {
  scene.illustration = { prompt: '', aspectRatio: scene.preset === 'media-right' ? '1:1' : '16:9' }
}

function removeIllustration(scene: OutlineScene): void {
  scene.illustration = null
}

const speechRunning = ref(false)
const speechProgress = ref<SceneProgress[]>([])
const speechText = ref('')
const speechError = ref('')
let speechAbort: AbortController | null = null

async function generateSpeech(scope: 'missing' | 'all'): Promise<void> {
  if (speechRunning.value || running.value) return
  if (scope === 'all' && !(await confirm('全部页面的讲稿和动作都会重写,已合成的语音随之作废。继续?', '重写全部讲稿'))) return
  speechProgress.value = []
  speechText.value = ''
  speechError.value = ''
  speechRunning.value = true
  emit('generating', true)
  speechAbort = new AbortController()
  try {
    await streamGenerateSpeech(props.courseId, props.coursewareId, scope, handleSpeechEvent, speechAbort.signal)
  } catch (error: unknown) {
    speechError.value =
      error instanceof DOMException && error.name === 'AbortError' ? '已取消;已生成的讲稿保留在课件里' : errorMessage(error)
  } finally {
    speechRunning.value = false
    emit('generating', false)
    emit('stageChanged')
    speechAbort = null
  }
}

function handleSpeechEvent(event: GenerationEvent): void {
  switch (event.type) {
    case 'scene_start':
      speechProgress.value.push({ order: event.order, title: event.title, status: 'running', message: '', traces: [] })
      break
    case 'trace':
      speechProgress.value.find((item) => item.order === event.order)?.traces.push(event.message)
      break
    case 'scene_done': {
      const item = speechProgress.value.find((entry) => entry.order === event.order)
      if (item) {
        item.status = 'done'
        item.message = event.warnings.join(';')
      }
      emit('stageChanged')
      break
    }
    case 'scene_failed': {
      const item = speechProgress.value.find((entry) => entry.order === event.order)
      if (item) {
        item.status = 'failed'
        item.message = event.message
      }
      break
    }
    case 'done': {
      const failed = event.failed ?? 0
      speechText.value =
        failed === 0
          ? `${event.total ?? speechProgress.value.length} 页的讲稿已生成,接下来合成语音`
          : `${(event.total ?? speechProgress.value.length) - failed} 页已生成,${failed} 页失败(可再点一次只补缺的)`
      break
    }
    case 'error':
      throw new Error(event.message)
    default:
      break
  }
}

function cancelSpeech(): void {
  speechAbort?.abort()
}

function backToInput(): void {
  step.value = 'input'
}

function backToOutline(): void {
  errorText.value = ''
  step.value = 'outline'
}

onMounted(() => {
  title.value = props.stageTitle
  void loadMaterials()
})
</script>

<template>
  <div class="generation-panel">
    <div v-if="running || speechRunning" class="panel-head">
      <span v-if="running" class="panel-status"><el-icon class="spin"><Loading /></el-icon>{{ step === 'generating' ? '逐页生成中' : '正在出大纲' }}</span>
      <span v-else-if="speechRunning" class="panel-status"><el-icon class="spin"><Loading /></el-icon>正在写讲稿</span>
      <span class="spacer" />
      <el-button v-if="running" text type="danger" size="small" @click="cancel">停止</el-button>
      <el-button v-else-if="speechRunning" text type="danger" size="small" @click="cancelSpeech">停止</el-button>
    </div>

    <div class="panel-body">
      <template v-if="step === 'input'">
        <section class="section">
          <div class="section-title">
            <span>素材(可选)</span>
            <input ref="fileInput" type="file" multiple :accept="MATERIAL_ACCEPT" hidden @change="onFilesPicked" />
            <el-button text size="small" :disabled="uploading || running" @click="pickFiles">上传</el-button>
          </div>
          <div v-if="materials.length || uploading" class="materials">
            <span v-for="m in materials" :key="m.id" class="material-chip" :title="`${m.chars} 字 · ${m.imageCount} 张图`">
              <span class="material-name">{{ m.name }}</span>
              <button type="button" class="material-remove" title="删除素材" :disabled="running" @click="removeMaterial(m)">×</button>
            </span>
            <span v-if="uploading" class="material-progress"><el-icon class="spin"><Loading /></el-icon>{{ uploadProgress }}</span>
          </div>
        </section>

        <section class="section">
          <div class="section-title"><span>教学需求</span></div>
          <el-input
            v-model="requirement"
            type="textarea"
            :rows="5"
            maxlength="4000"
            resize="none"
            :disabled="running"
            placeholder="这门课讲什么、讲给谁、重点难点、希望的风格…"
          />
        </section>

        <section class="section">
          <div class="section-title"><span>页数(可选,不填由模型规划)</span></div>
          <div class="counts">
            <label>讲解页<el-input-number v-model="contentCount" size="small" :min="2" :max="LIMITS.sceneCountMax - 1" :disabled="running" /></label>
            <label>测验页<el-input-number v-model="quizCount" size="small" :min="0" :max="LIMITS.quizCountMax" :disabled="running" /></label>
            <label>交互页<el-input-number v-model="interactiveCount" size="small" :min="0" :max="LIMITS.interactiveCountMax" :disabled="running" /></label>
          </div>
          <p v-if="countsError" class="hint error">{{ countsError }}</p>
          <p v-else-if="totalScenes !== null" class="hint">共 {{ totalScenes }} 页（含封面）</p>
        </section>

        <ul v-if="outlineTraces.length" class="traces">
          <li v-for="(trace, i) in outlineTraces" :key="i">{{ trace }}</li>
        </ul>
        <el-alert v-if="errorText" type="error" :title="errorText" :closable="false" />
        <el-button type="primary" class="primary-action" :loading="running" :disabled="!!countsError || uploading" @click="generateOutline">
          生成大纲
        </el-button>
      </template>

      <template v-else-if="step === 'outline'">
        <section class="section">
          <div class="section-title"><span>课件</span></div>
          <el-input v-model="title" placeholder="课件标题" maxlength="255" />
        </section>

        <div class="scene-bar">
          <span v-for="type in (['content', 'quiz', 'interactive'] as OutlineSceneType[])" :key="type" class="scene-cell">
            {{ type === 'content' ? '讲解' : type === 'quiz' ? '测验' : '交互' }}
            <el-button size="small" circle :disabled="sceneCounts[type] === 0" @click="adjustScene(type, -1)">−</el-button>
            <b>{{ sceneCounts[type] }}</b>
            <el-button size="small" circle :disabled="outline.length >= LIMITS.outlineMaxScenes" @click="adjustScene(type, 1)">+</el-button>
          </span>
          <span class="scene-total">共 {{ outline.length }} 页</span>
        </div>

        <div v-for="(scene, i) in outline" :key="i" class="outline-item">
          <div class="outline-row">
            <span class="outline-index">{{ i + 1 }}</span>
            <el-input v-model="scene.title" placeholder="页面标题" maxlength="200" />
            <el-button text size="small" :disabled="i === 0" title="上移" @click="moveOutlineScene(i, -1)">↑</el-button>
            <el-button text size="small" :disabled="i === outline.length - 1" title="下移" @click="moveOutlineScene(i, 1)">↓</el-button>
            <el-button text size="small" type="danger" @click="removeOutlineScene(i)">删</el-button>
          </div>
          <div class="outline-row">
            <el-select :model-value="scene.type" class="type-select" @update:model-value="(t: OutlineSceneType) => changeSceneType(i, t)">
              <el-option label="讲解页" value="content" />
              <el-option label="测验页" value="quiz" />
              <el-option label="交互页" value="interactive" />
            </el-select>
            <el-select v-if="scene.type === 'interactive'" v-model="scene.widgetType" class="preset-select" placeholder="组件类型">
              <el-option v-for="w in WIDGET_OPTIONS" :key="w.value" :label="w.label" :value="w.value" />
            </el-select>
            <el-select v-else-if="scene.type === 'content'" v-model="scene.preset" class="preset-select">
              <el-option v-for="p in PRESET_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
            </el-select>
            <el-select
              v-if="scene.type === 'content' && imageOptions.length"
              v-model="scene.imageIds"
              class="image-select"
              multiple
              collapse-tags
              clearable
              placeholder="配图"
              :multiple-limit="LIMITS.sceneImagesMax"
            >
              <el-option v-for="image in imageOptions" :key="image.id" :label="image.label" :value="image.id">
                <span class="image-option"><img :src="image.url" :alt="image.id" /><span>{{ image.label }}</span></span>
              </el-option>
            </el-select>
          </div>
          <el-input v-model="scene.summary" type="textarea" :rows="2" resize="none" maxlength="2000" placeholder="这页讲什么(生成内容的依据)" />
          <ul class="key-points">
            <li v-for="(_, k) in scene.keyPoints" :key="k" class="key-point">
              <span class="key-point-dot">·</span>
              <el-input v-model="scene.keyPoints[k]" size="small" :maxlength="LIMITS.keyPointChars" placeholder="一条要点,一句话" />
              <el-button text size="small" type="danger" title="删掉这条要点" @click="removeKeyPoint(scene, k)">×</el-button>
            </li>
            <li v-if="scene.keyPoints.length < LIMITS.keyPointsMax">
              <el-button text size="small" @click="addKeyPoint(scene)">+ 要点</el-button>
            </li>
          </ul>
          <div v-if="scene.type === 'content'" class="illustration">
            <template v-if="scene.illustration">
              <div class="illustration-head">
                <span class="illustration-label">AI 配图</span>
                <el-select v-model="scene.illustration.aspectRatio" size="small" class="ratio-select">
                  <el-option v-for="ratio in IMAGE_ASPECT_RATIOS" :key="ratio" :label="IMAGE_ASPECT_RATIO_LABELS[ratio]" :value="ratio" />
                </el-select>
                <el-button text size="small" type="danger" title="不要这张配图" @click="removeIllustration(scene)">×</el-button>
              </div>
              <el-input
                v-model="scene.illustration.prompt"
                type="textarea"
                :rows="2"
                resize="none"
                :maxlength="LIMITS.imagePromptChars"
                placeholder="画面内容、布局、风格"
              />
            </template>
            <el-button v-else text size="small" @click="addIllustration(scene)">+ AI 配图</el-button>
          </div>
        </div>

        <el-alert v-if="errorText" type="error" :title="errorText" :closable="false" />
        <div class="actions">
          <el-button @click="backToInput">返回修改需求</el-button>
          <el-button type="primary" :loading="running" @click="generateStage">按此大纲生成</el-button>
        </div>
      </template>

      <template v-else>
        <ul class="progress">
          <li v-for="item in progress" :key="item.order" class="progress-item" :class="item.status">
            <span class="progress-index">{{ item.order }}</span>
            <span class="progress-title">{{ item.title }}</span>
            <el-icon v-if="item.status === 'running'" class="spin"><Loading /></el-icon>
            <span v-else class="progress-state">
              {{ item.status === 'done' ? '已生成' : item.status === 'failed' ? '失败' : '等待中' }}
            </span>
            <div v-if="item.message" class="progress-message">{{ item.message }}</div>
            <ul v-if="item.traces.length" class="traces">
              <li v-for="(trace, i) in item.traces" :key="i">{{ trace }}</li>
            </ul>
          </li>
        </ul>
        <el-alert v-if="finishedText" type="success" :title="finishedText" :closable="false" />
        <el-alert v-if="errorText" type="error" :title="errorText" :closable="false" />
        <div v-if="step === 'finished'" class="actions">
          <el-button @click="backToOutline">回到大纲</el-button>
          <el-button @click="backToInput">重新开始</el-button>
        </div>
      </template>

      <section v-if="sceneCount > 0 && (step === 'input' || step === 'finished')" class="speech-step">
        <div class="speech-head">
          <strong>讲稿与语音</strong>
          <span class="speech-state">
            {{ speechMissing > 0 ? `${speechMissing} 页还没有讲稿` : '每页都有讲稿' }}
            <template v-if="speechMissing < sceneCount">
              · {{ unvoicedSegments > 0 ? `${unvoicedSegments} 段还没有语音` : '语音已齐' }}
            </template>
          </span>
        </div>
        <div class="actions">
          <el-button
            type="primary"
            :disabled="running || speechRunning || synthesizing || speechMissing === 0"
            :loading="speechRunning"
            @click="generateSpeech('missing')"
          >
            {{ speechMissing > 0 ? `给 ${speechMissing} 页写讲稿` : '讲稿已齐' }}
          </el-button>
          <el-button :disabled="running || speechRunning || synthesizing" @click="generateSpeech('all')">全部重写</el-button>
          <el-button
            :disabled="running || speechRunning || synthesizing || speechMissing === sceneCount || unvoicedSegments === 0"
            :loading="synthesizing"
            @click="emit('synthesize')"
          >
            {{ unvoicedSegments > 0 ? `合成 ${unvoicedSegments} 段语音` : speechMissing < sceneCount ? '语音已齐' : '合成语音' }}
          </el-button>
        </div>
        <ul v-if="speechProgress.length" class="progress">
          <li v-for="item in speechProgress" :key="item.order" class="progress-item" :class="item.status">
            <span class="progress-index">{{ item.order }}</span>
            <span class="progress-title">{{ item.title }}</span>
            <el-icon v-if="item.status === 'running'" class="spin"><Loading /></el-icon>
            <span v-else class="progress-state">{{ item.status === 'done' ? '已写' : item.status === 'failed' ? '失败' : '等待中' }}</span>
            <div v-if="item.message" class="progress-message">{{ item.message }}</div>
            <ul v-if="item.traces.length" class="traces">
              <li v-for="(trace, i) in item.traces" :key="i">{{ trace }}</li>
            </ul>
          </li>
        </ul>
        <el-alert v-if="speechText" type="success" :title="speechText" :closable="false" />
        <el-alert v-if="speechError" type="error" :title="speechError" :closable="false" />
      </section>
    </div>
  </div>
</template>

<style scoped>
.generation-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.speech-step {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.speech-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.speech-state {
  font-size: 12.5px;
  color: var(--text-secondary, #6b7280);
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}

.panel-status {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--brand);
}

.spacer {
  flex: 1;
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 14px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
}

.hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}

.hint.error {
  color: var(--warning);
}

.materials {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.material-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  padding: 2px 8px;
  border: 1px solid var(--border);
  border-radius: 999px;
  font-size: 12px;
  color: var(--text-secondary);
}

.material-name {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.material-remove {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}

.material-progress {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.counts {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  font-size: 12.5px;
}

.counts label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.primary-action {
  align-self: flex-start;
}

.scene-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 16px;
  padding: 8px 10px;
  background: var(--surface-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  font-size: 12.5px;
}

.scene-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.scene-total {
  margin-left: auto;
  color: var(--text-muted);
}

.outline-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
}

.key-points {
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
}

.key-point {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
}

.key-point-dot {
  width: 12px;
  text-align: center;
  color: var(--text-secondary, #6b7280);
}

.illustration {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 4px;
}

.illustration-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.illustration-label {
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}

.ratio-select {
  width: 112px;
  flex: none;
}

.outline-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.outline-index {
  width: 20px;
  flex: none;
  color: var(--text-muted);
  font-size: 12px;
  text-align: right;
}

.type-select {
  width: 96px;
  flex: none;
}

.preset-select {
  width: 118px;
  flex: none;
}

.image-select {
  flex: 1;
  min-width: 0;
}

.image-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.image-option img {
  width: 40px;
  height: 28px;
  object-fit: contain;
}

.actions {
  display: flex;
  gap: 8px;
}

.progress {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.progress-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  font-size: 12.5px;
}

.progress-item.done {
  border-color: var(--success, #3a9d5d);
}

.progress-item.failed {
  border-color: var(--danger, #d0433b);
}

.progress-index {
  color: var(--text-muted);
}

.progress-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.progress-state {
  color: var(--text-muted);
}

.progress-item.failed .progress-state {
  color: var(--danger, #d0433b);
}

.progress-message {
  flex-basis: 100%;
  font-size: 12px;
  color: var(--text-secondary);
}

.traces {
  flex-basis: 100%;
  margin: 0;
  padding-left: 16px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-muted);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
