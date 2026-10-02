<script setup lang="ts">
/**
 * 课件工作台:左侧生成流水线(素材 → 需求 → 大纲 → 逐页生成),右侧课件(页条 + 可直接编辑的画布 + 手工编辑面板)。
 * 课件没有"未保存"态——手工编辑的每个改动都是一条编辑操作,即时落库;
 * 撤销 / 重做是反向操作再落库(删页不可逆,生成写入后历史作废)。
 * 生成流水线每落库一页本视图就重取课件;单页重生成从画布上方发起。发布是独立的布尔:未发布的课件学生看不到。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { confirm, prompt } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { CoursewareImageView } from '@/api/generated'
import { streamRegenerateScene, streamSynthesizeTts } from '@/features/courseware/coursewareStream'
import type { Block, Stage, EditOp, Scene, PresetName, WidgetType } from '@/features/courseware/dsl'
import { PRESET_LABELS, PRESET_NAMES, WIDGET_TYPES, WIDGET_TYPE_LABELS, isBlocklessScene } from '@/features/courseware/dsl'
import { DEFAULT_THEME, stripInline } from '@/features/courseware/layout'
import { createHistory, invertOps } from '@/features/courseware/editor/history'
import { nextBlockId, pasteOps, type BlockClip } from '@/features/courseware/editor/clipboard'
import InteractiveHost from '@/features/courseware/render/InteractiveHost.vue'
import VideoHost from '@/features/courseware/render/VideoHost.vue'
import BlockEditPanel from '@/features/courseware/components/workbench/BlockEditPanel.vue'
import CanvasEditor from '@/features/courseware/components/workbench/CanvasEditor.vue'
import GenerationPanel from '@/features/courseware/components/workbench/GenerationPanel.vue'
import HtmlSourceInput from '@/features/courseware/components/workbench/HtmlSourceInput.vue'
import VideoUploadInput from '@/features/courseware/components/workbench/VideoUploadInput.vue'
import ImageInsertDialog from '@/features/courseware/components/workbench/ImageInsertDialog.vue'
import SpeechEditPanel from '@/features/courseware/components/workbench/SpeechEditPanel.vue'
import SceneList from '@/features/courseware/components/workbench/SceneList.vue'

const route = useRoute()
const router = useRouter()
const courseId = computed(() => Number(route.params.courseId))
const coursewareId = computed(() => Number(route.params.coursewareId))

const stage = ref<Stage | null>(null)
const assetUrls = ref<Record<string, string>>({})
const published = ref(false)
const loading = ref(true)
const loadError = ref('')

const currentSceneId = ref<string | null>(null)
const rightTab = ref<'blocks' | 'speech'>('blocks')
const generationOpen = ref(false)
const applying = ref(false)
/** 生成流水线或单页重生成进行中:课件正被后台写入,手工编辑暂停 */
const generating = ref(false)
const busy = computed(() => applying.value || generating.value)
const exporting = ref(false)
const publishing = ref(false)
const ttsRunning = ref(false)
const ttsProgress = ref('')

const hasSpeech = computed(() => (stage.value?.scenes ?? []).some((scene) => scene.speech.length > 0))
const speechMissing = computed(() => (stage.value?.scenes ?? []).filter((scene) => scene.speech.length === 0).length)
const unvoicedSegments = computed(() =>
  (stage.value?.scenes ?? []).reduce((sum, scene) => sum + scene.speech.filter((segment) => !segment.audioPath).length, 0),
)

const currentScene = computed<Scene | null>(
  () => stage.value?.scenes.find((p) => p.id === currentSceneId.value) ?? null,
)

const selectedId = ref<string | null>(null)
function select(blockId: string | null): void {
  selectedId.value = blockId
}

function applyDetail(detail: {
  stage: unknown
  assetUrls: Record<string, string>
  published: boolean
}): void {
  stage.value = detail.stage as Stage
  assetUrls.value = detail.assetUrls
  published.value = detail.published
  if (!stage.value.scenes.some((p) => p.id === currentSceneId.value)) {
    currentSceneId.value = stage.value.scenes[0]?.id ?? null
  }
  if (selectedId.value && !currentScene.value?.blocks.some((b) => b.id === selectedId.value)) {
    selectedId.value = null
  }
}

const history = createHistory()
const canUndo = ref(false)
const canRedo = ref(false)
function syncHistory(): void {
  canUndo.value = history.canUndo
  canRedo.value = history.canRedo
}

async function undo(): Promise<void> {
  const entry = history.undo()
  if (!entry) return
  if (!(await applyOps(entry.undo, { record: false }))) history.clear()
  syncHistory()
}

async function redo(): Promise<void> {
  const entry = history.redo()
  if (!entry) return
  if (!(await applyOps(entry.redo, { record: false }))) history.clear()
  syncHistory()
}

async function load(): Promise<void> {
  try {
    const response = await api.coursewareGet(courseId.value, coursewareId.value)
    applyDetail(response.data)
    loadError.value = ''
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

/** 生成写入后重取:合并抖动(多页可能连着落库);课件已不是教师上次看到的样子,撤销历史作废 */
let reloadTimer: ReturnType<typeof setTimeout> | null = null
function scheduleReload(): void {
  history.clear()
  syncHistory()
  if (reloadTimer) clearTimeout(reloadTimer)
  reloadTimer = setTimeout(() => {
    reloadTimer = null
    void load()
  }, 300)
}

/**
 * 手工编辑:整批操作即时落库;被拒(400)时提示并重取以对齐草稿。
 * record 为真时按改动前后的课件推出反向操作入撤销栈;推不出(删页)则清栈。返回是否成功。
 */
async function applyOps(ops: EditOp[], options: { record?: boolean } = {}): Promise<boolean> {
  if (busy.value || !stage.value) return false
  const before = stage.value
  applying.value = true
  try {
    const response = await api.coursewareApplyOps(courseId.value, coursewareId.value, {
      ops: ops as unknown as Record<string, unknown>[],
    })
    applyDetail(response.data)
    if (options.record !== false) {
      const inverse = invertOps(ops, before, stage.value as Stage)
      if (inverse) history.push({ undo: inverse, redo: ops })
      else history.clear()
      syncHistory()
    }
    return true
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
    await load()
    return false
  } finally {
    applying.value = false
  }
}

const imageDialogVisible = ref(false)
const replacingImageId = ref<string | null>(null)

function openInsertImage(): void {
  replacingImageId.value = null
  imageDialogVisible.value = true
}

function openReplaceImage(blockId: string): void {
  replacingImageId.value = blockId
  imageDialogVisible.value = true
}

async function onImagePicked(image: CoursewareImageView): Promise<void> {
  const scene = currentScene.value
  if (!scene) return
  const replacing = replacingImageId.value
  if (replacing) {
    const block = scene.blocks.find((b) => b.id === replacing)
    if (!block || block.type !== 'image') return
    await applyOps([
      {
        op: 'replace_block',
        sceneId: scene.id,
        blockId: block.id,
        block: { ...block, src: image.src, width: image.width, height: image.height },
      },
    ])
    return
  }
  const id = nextBlockId(scene, 'image')
  const block: Block = { id, type: 'image', src: image.src, width: image.width, height: image.height }
  if (await applyOps([{ op: 'add_block', sceneId: scene.id, index: scene.blocks.length, block }])) {
    selectedId.value = id
  }
}

const regenerateVisible = ref(false)
const regenerateForm = ref<{ scope: 'content' | 'speech'; instruction: string }>({ scope: 'content', instruction: '' })
const regenerateTraces = ref<string[]>([])
let regenerateAbort: AbortController | null = null

function openRegenerate(): void {
  regenerateForm.value = { scope: currentScene.value?.type === 'video' ? 'speech' : 'content', instruction: '' }
  regenerateTraces.value = []
  regenerateVisible.value = true
}

async function submitRegenerate(): Promise<void> {
  const scene = currentScene.value
  if (!scene || generating.value) return
  generating.value = true
  regenerateTraces.value = []
  regenerateAbort = new AbortController()
  try {
    await streamRegenerateScene(
      courseId.value,
      coursewareId.value,
      scene.id,
      {
        scope: regenerateForm.value.scope,
        instruction: regenerateForm.value.instruction.trim() || null,
      },
      (event) => {
        if (event.type === 'trace') regenerateTraces.value.push(event.message)
        else if (event.type === 'scene_done' && event.warnings.length) regenerateTraces.value.push(...event.warnings)
        else if (event.type === 'error') throw new Error(event.message)
      },
      regenerateAbort.signal,
    )
    regenerateVisible.value = false
    ElMessage.success(regenerateForm.value.scope === 'speech' ? '讲稿已重新生成' : '本页已重新生成')
  } catch (error: unknown) {
    if (!(error instanceof DOMException && error.name === 'AbortError')) ElMessage.error(errorMessage(error))
  } finally {
    generating.value = false
    regenerateAbort = null
    history.clear()
    syncHistory()
    await load()
  }
}

function cancelRegenerate(): void {
  regenerateAbort?.abort()
}

const replaceHtmlVisible = ref(false)
const replaceHtml = ref('')

function openReplaceHtml(): void {
  replaceHtml.value = ''
  replaceHtmlVisible.value = true
}

async function submitReplaceHtml(): Promise<void> {
  const scene = currentScene.value
  if (!scene || scene.type !== 'interactive') return
  if (!replaceHtml.value.trim()) {
    ElMessage.warning('请粘贴或选择网页文件')
    return
  }
  if (await applyOps([{ op: 'set_interactive_html', sceneId: scene.id, html: replaceHtml.value }])) {
    replaceHtmlVisible.value = false
  }
}

const replaceVideoVisible = ref(false)

function openReplaceVideo(): void {
  replaceVideoVisible.value = true
}

async function onReplacementVideoUploaded(video: { src: string }): Promise<void> {
  const scene = currentScene.value
  if (!scene || scene.type !== 'video') return
  if (await applyOps([{ op: 'set_video', sceneId: scene.id, src: video.src }])) {
    replaceVideoVisible.value = false
  }
}

const pinnedCount = computed(() => currentScene.value?.layouts?.filter((l) => l.frame).length ?? 0)

function resetScenePins(): void {
  const scene = currentScene.value
  if (!scene) return
  const ops: EditOp[] = (scene.layouts ?? [])
    .filter((l) => l.frame)
    .map((l) => ({ op: 'unpin_block', sceneId: scene.id, blockId: l.blockId }))
  if (ops.length > 0) void applyOps(ops)
}

let clipboard: BlockClip | null = null

function isTypingTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el) return false
  return el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable
}

function copySelected(): boolean {
  const scene = currentScene.value
  const block = scene?.blocks.find((b) => b.id === selectedId.value)
  if (!scene || !block) return false
  clipboard = { block, layout: scene.layouts?.find((l) => l.blockId === block.id) }
  return true
}

async function pasteClipboard(): Promise<void> {
  const scene = currentScene.value
  if (!scene || !clipboard || isBlocklessScene(scene.type)) return
  if (clipboard.block.type === 'quiz_choice' && scene.type !== 'quiz') {
    ElMessage.warning('选择题只能粘贴到测验页')
    return
  }
  const { ops, blockId } = pasteOps(scene, clipboard, DEFAULT_THEME.canvas)
  if (await applyOps(ops)) selectedId.value = blockId
}

function onKeyDown(event: KeyboardEvent): void {
  if (isTypingTarget(event.target) || !(event.ctrlKey || event.metaKey)) return
  const key = event.key.toLowerCase()
  if (key === 'z' && !event.shiftKey) {
    event.preventDefault()
    void undo()
  } else if (key === 'y' || (key === 'z' && event.shiftKey)) {
    event.preventDefault()
    void redo()
  } else if (key === 'c') {
    if (copySelected()) event.preventDefault()
  } else if (key === 'v') {
    if (clipboard) {
      event.preventDefault()
      void pasteClipboard()
    }
  } else if (key === 'd') {
    if (copySelected()) {
      event.preventDefault()
      void pasteClipboard()
    }
  }
}

async function renameStage(): Promise<void> {
  if (!stage.value) return
  const title = await prompt('课件标题', '重命名', {
    value: stage.value.title,
    validate: (v) => (v.trim() ? null : '标题不能为空'),
  })
  if (title !== null && title.trim() !== stage.value.title) {
    await applyOps([{ op: 'update_stage_meta', title: title.trim() }])
  }
}

function moveScene(scene: Scene, dir: -1 | 1): void {
  if (!stage.value) return
  const index = stage.value.scenes.indexOf(scene)
  const target = index + dir
  if (target < 0 || target >= stage.value.scenes.length) return
  void applyOps([{ op: 'move_scene', sceneId: scene.id, toIndex: target }])
}

async function deleteScene(scene: Scene): Promise<void> {
  if (!(await confirm(`删除第 ${sceneOrder(scene)} 页「${stripInline(scene.title)}」?`, '删除页面'))) return
  await applyOps([{ op: 'delete_scene', sceneId: scene.id }])
}

function sceneOrder(scene: Scene): number {
  return (stage.value?.scenes.indexOf(scene) ?? -1) + 1
}

const addVisible = ref(false)
const addForm = ref<{
  title: string
  type: 'content' | 'quiz' | 'interactive' | 'video'
  preset: PresetName
  summary: string
  html: string
  widgetType: WidgetType | ''
  videoSrc: string
}>({ title: '', type: 'content', preset: 'standard', summary: '', html: '', widgetType: '', videoSrc: '' })

function openAdd(): void {
  addForm.value = { title: '', type: 'content', preset: 'standard', summary: '', html: '', widgetType: '', videoSrc: '' }
  addVisible.value = true
}

function onAddTypeChange(): void {
  addForm.value.preset = addForm.value.type === 'quiz' ? 'quiz' : 'standard'
}

async function submitAdd(): Promise<void> {
  const form = addForm.value
  if (!form.title.trim()) {
    ElMessage.warning('请填写页面标题')
    return
  }
  const index = currentScene.value ? sceneOrder(currentScene.value) : (stage.value?.scenes.length ?? 0)
  if (form.type === 'video') {
    if (!form.videoSrc) {
      ElMessage.warning('请先上传视频')
      return
    }
    const op: EditOp = { op: 'add_video_scene', index, title: form.title.trim(), summary: form.summary.trim(), src: form.videoSrc }
    if (await applyOps([op])) {
      addVisible.value = false
      const added = stage.value?.scenes[index]
      if (added) currentSceneId.value = added.id
    }
    return
  }
  if (form.type === 'interactive') {
    if (!form.html.trim()) {
      ElMessage.warning('请粘贴或选择网页文件')
      return
    }
    const op: EditOp = {
      op: 'add_interactive_scene',
      index,
      title: form.title.trim(),
      summary: form.summary.trim(),
      html: form.html,
      ...(form.widgetType ? { widgetType: form.widgetType } : {}),
    }
    if (await applyOps([op])) {
      addVisible.value = false
      const added = stage.value?.scenes[index]
      if (added) currentSceneId.value = added.id
    }
    return
  }
  await applyOps([
    {
      op: 'add_scene',
      index,
      title: form.title.trim(),
      type: form.type,
      preset: form.preset,
      summary: form.summary.trim(),
      blocks:
        form.type === 'quiz'
          ? [
              {
                id: 'blk-quiz_choice-1',
                type: 'quiz_choice',
                stem: '题干',
                options: [
                  { label: 'A', text: '选项甲' },
                  { label: 'B', text: '选项乙' },
                ],
                answer: ['A'],
                multiple: false,
                explanation: '讲解',
              },
            ]
          : [{ id: 'blk-paragraph-1', type: 'paragraph', text: '正文' }],
    },
  ])
  addVisible.value = false
  const added = stage.value?.scenes[index]
  if (added) currentSceneId.value = added.id
}

async function togglePublish(): Promise<void> {
  publishing.value = true
  try {
    const response = published.value
      ? await api.coursewareUnpublish(courseId.value, coursewareId.value)
      : await api.coursewarePublish(courseId.value, coursewareId.value)
    published.value = response.data.published
    ElMessage.success(published.value ? '已发布,学生可以学习了' : '已取消发布')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    publishing.value = false
  }
}

async function synthesize(): Promise<void> {
  ttsRunning.value = true
  ttsProgress.value = ''
  try {
    const summary = await streamSynthesizeTts(courseId.value, coursewareId.value, (done, total) => {
      ttsProgress.value = `${done}/${total}`
    })
    await load()
    ElMessage.success(
      summary.generated > 0
        ? `已合成 ${summary.generated} 段讲稿语音`
        : summary.skipped > 0
          ? '所有讲稿段都已有语音'
          : '还没有讲稿,没有可合成的内容',
    )
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    ttsRunning.value = false
  }
}

async function exportPptx(): Promise<void> {
  exporting.value = true
  try {
    const response = await api.coursewareExportPptx(courseId.value, coursewareId.value, {
      responseType: 'blob',
    })
    const blob = response.data as unknown as Blob
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${stage.value ? stripInline(stage.value.title) : '课件'}.pptx`
    a.click()
    URL.revokeObjectURL(url)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    exporting.value = false
  }
}

function openPreview(): void {
  void router.push(`/focus/admin/courses/${courseId.value}/coursewares/${coursewareId.value}/preview`)
}

function backToCourse(): void {
  void router.push(`/admin/courses/${courseId.value}?tab=courseware`)
}

const canvasArea = ref<HTMLElement | null>(null)
const canvasWidth = ref(720)
let observer: ResizeObserver | null = null

function fitCanvas(): void {
  const el = canvasArea.value
  if (!el) return
  // 画布下方还有一行状态文字,高度上留出它的位置
  canvasWidth.value = Math.max(320, Math.floor(Math.min(el.clientWidth - 48, ((el.clientHeight - 72) * 1280) / 720)))
}

onMounted(async () => {
  window.addEventListener('keydown', onKeyDown)
  await load()
  if (stage.value?.scenes.length === 0) generationOpen.value = true
  if (canvasArea.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(fitCanvas)
    observer.observe(canvasArea.value)
  }
  fitCanvas()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
  observer?.disconnect()
  if (reloadTimer) clearTimeout(reloadTimer)
})

</script>

<template>
  <div class="workbench">
    <header class="workbench-header">
      <el-page-header @back="backToCourse">
        <template #content>
          <span v-if="stage" class="stage-title">
            <button type="button" class="link-text" title="重命名" @click="renameStage">
              {{ stripInline(stage.title) }}
            </button>
            <el-tag size="small" :type="published ? 'success' : 'info'">{{ published ? '已发布' : '未发布' }}</el-tag>
          </span>
          <span v-else>加载中…</span>
        </template>
      </el-page-header>
      <div class="header-actions">
        <el-button :disabled="!stage" @click="generationOpen = true">生成课件</el-button>
        <el-button
          text
          bg
          :disabled="ttsRunning || !stage || !hasSpeech"
          :title="hasSpeech ? undefined : '还没有讲稿'"
          @click="synthesize"
        >
          {{ ttsRunning ? `合成语音中 ${ttsProgress}` : '合成语音' }}
        </el-button>
        <el-button text bg :loading="exporting" :disabled="!stage" @click="exportPptx">导出 PPTX</el-button>
        <el-button :disabled="!stage || stage.scenes.length === 0" @click="openPreview">预览播放</el-button>
        <el-button
          :type="published ? 'default' : 'primary'"
          :loading="publishing"
          :disabled="!stage || (!published && stage.scenes.length === 0)"
          @click="togglePublish"
        >
          {{ published ? '取消发布' : '发布' }}
        </el-button>
      </div>
    </header>

    <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" />
    <div v-else-if="loading" class="workbench-loading">课件加载中…</div>

    <div v-else-if="stage" class="workbench-body">
      <aside class="scene-rail">
        <SceneList
          :scenes="stage.scenes"
          :current-id="currentSceneId"
          :busy="busy"
          @select="(id) => (currentSceneId = id)"
          @move="moveScene"
          @remove="deleteScene"
          @add="openAdd"
        />
      </aside>

      <main class="stage-area">
        <div class="canvas-toolbar">
          <el-button text size="small" :disabled="!canUndo || busy" title="撤销 (Ctrl+Z)" @click="undo">撤销</el-button>
          <el-button text size="small" :disabled="!canRedo || busy" title="重做 (Ctrl+Y)" @click="redo">重做</el-button>
          <el-divider direction="vertical" />
          <el-button
            text
            size="small"
            :disabled="busy || !currentScene || isBlocklessScene(currentScene.type)"
            @click="openInsertImage"
          >
            插入图片
          </el-button>
          <el-button text size="small" :disabled="busy || !currentScene" @click="openRegenerate">重生成本页</el-button>
          <el-button v-if="pinnedCount > 0" text size="small" :disabled="busy" @click="resetScenePins">
            本页恢复自动排版({{ pinnedCount }})
          </el-button>
        </div>
        <div ref="canvasArea" class="canvas-area">
          <template v-if="currentScene">
            <div
              v-if="currentScene.type === 'interactive'"
              class="interactive-frame"
              :style="{ width: `${canvasWidth}px`, height: `${(canvasWidth * 720) / 1280}px` }"
            >
              <InteractiveHost :key="currentScene.interactive?.html ?? ''" :scene="currentScene" />
            </div>
            <div
              v-else-if="currentScene.type === 'video'"
              class="interactive-frame"
              :style="{ width: `${canvasWidth}px`, height: `${(canvasWidth * 720) / 1280}px` }"
            >
              <VideoHost :key="currentScene.video?.src ?? ''" :scene="currentScene" :asset-urls="assetUrls" />
            </div>
            <CanvasEditor
              v-else
              :scene="currentScene"
              :width="canvasWidth"
              :asset-urls="assetUrls"
              :selected-id="selectedId"
              :busy="busy"
              @select="select"
              @ops="applyOps"
            />
          </template>
        </div>
      </main>

      <aside class="edit-rail">
        <el-tabs v-model="rightTab">
          <el-tab-pane label="内容" name="blocks">
            <BlockEditPanel
              v-if="currentScene"
              :key="currentScene.id"
              :scene="currentScene"
              :asset-urls="assetUrls"
              :busy="busy"
              :selected-id="selectedId"
              @ops="applyOps"
              @select="select"
              @insert-image="openInsertImage"
              @replace-image="openReplaceImage"
              @replace-interactive="openReplaceHtml"
              @replace-video="openReplaceVideo"
            />
            <p v-else class="rail-empty">选择一页后在这里编辑</p>
          </el-tab-pane>
          <el-tab-pane label="讲稿" name="speech">
            <SpeechEditPanel
              v-if="currentScene"
              :key="currentScene.id"
              :scene="currentScene"
              :busy="busy"
              @ops="applyOps"
            />
            <p v-else class="rail-empty">选择一页后在这里编辑讲稿</p>
          </el-tab-pane>
        </el-tabs>
      </aside>
    </div>

    <el-drawer
      v-if="stage"
      v-model="generationOpen"
      title="生成课件"
      direction="rtl"
      size="420px"
      :modal="false"
      :lock-scroll="false"
      body-class="generation-drawer-body"
    >
      <GenerationPanel
        :course-id="courseId"
        :courseware-id="coursewareId"
        :stage-title="stage.title"
        :scene-count="stage.scenes.length"
        :speech-missing="speechMissing"
        :unvoiced-segments="unvoicedSegments"
        :synthesizing="ttsRunning"
        @stage-changed="scheduleReload"
        @generating="(value) => (generating = value)"
        @synthesize="synthesize"
      />
    </el-drawer>

    <ImageInsertDialog
      v-model:visible="imageDialogVisible"
      :course-id="courseId"
      :courseware-id="coursewareId"
      :replacing="replacingImageId !== null"
      @picked="onImagePicked"
    />

    <el-dialog v-model="regenerateVisible" title="重生成本页" width="480px" :close-on-click-modal="!generating">
      <el-form label-width="80px">
        <el-form-item label="范围">
          <el-radio-group v-model="regenerateForm.scope" :disabled="generating">
            <el-radio value="content" :disabled="currentScene?.type === 'video'">整页内容(讲稿清空、手工排版归零,改定后再生成讲稿)</el-radio>
            <el-radio value="speech">只重写讲稿</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="调整要求">
          <el-input
            v-model="regenerateForm.instruction"
            type="textarea"
            :rows="3"
            maxlength="2000"
            :disabled="generating"
            placeholder="比如“换一个生活里的例子”"
          />
        </el-form-item>
      </el-form>
      <ul v-if="regenerateTraces.length" class="regenerate-traces">
        <li v-for="(trace, i) in regenerateTraces" :key="i">{{ trace }}</li>
      </ul>
      <template #footer>
        <el-button v-if="generating" type="danger" text @click="cancelRegenerate">停止</el-button>
        <el-button v-else @click="regenerateVisible = false">取消</el-button>
        <el-button type="primary" :loading="generating" @click="submitRegenerate">开始重生成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addVisible" title="添加页面" :width="addForm.type === 'content' || addForm.type === 'quiz' ? '460px' : '720px'">
      <el-form label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="addForm.title" maxlength="120" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="addForm.type" @change="onAddTypeChange">
            <el-radio value="content">讲解页</el-radio>
            <el-radio value="quiz">测验页</el-radio>
            <el-radio value="interactive">交互页</el-radio>
            <el-radio value="video">视频页</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="addForm.type === 'video'">
          <el-form-item label="概要">
            <el-input v-model="addForm.summary" type="textarea" :rows="2" placeholder="视频讲什么" />
          </el-form-item>
          <el-form-item label="视频">
            <VideoUploadInput
              :course-id="courseId"
              :courseware-id="coursewareId"
              :disabled="applying"
              @uploaded="(video) => (addForm.videoSrc = video.src)"
            />
          </el-form-item>
        </template>
        <template v-if="addForm.type === 'interactive'">
          <el-form-item label="组件类型">
            <el-select v-model="addForm.widgetType" clearable placeholder="可不填,只作标注">
              <el-option v-for="w in WIDGET_TYPES" :key="w" :label="WIDGET_TYPE_LABELS[w]" :value="w" />
            </el-select>
          </el-form-item>
          <el-form-item label="概要">
            <el-input v-model="addForm.summary" type="textarea" :rows="2" placeholder="这页让学生做什么" />
          </el-form-item>
          <el-form-item label="网页">
            <HtmlSourceInput v-model="addForm.html" :disabled="applying" />
          </el-form-item>
        </template>
        <el-form-item v-if="!isBlocklessScene(addForm.type)" label="布局">
          <el-select v-model="addForm.preset">
            <el-option
              v-for="p in PRESET_NAMES.filter((name) => (addForm.type === 'quiz') === (name === 'quiz'))"
              :key="p"
              :label="PRESET_LABELS[p]"
              :value="p"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!isBlocklessScene(addForm.type)" label="概要">
          <el-input v-model="addForm.summary" type="textarea" :rows="2" placeholder="这页要讲什么" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="applying" @click="submitAdd">添加</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="replaceVideoVisible" title="换视频" width="640px">
      <VideoUploadInput
        :course-id="courseId"
        :courseware-id="coursewareId"
        :disabled="applying"
        @uploaded="onReplacementVideoUploaded"
      />
      <template #footer>
        <el-button @click="replaceVideoVisible = false">取消</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="replaceHtmlVisible" title="替换交互网页" width="720px">
      <HtmlSourceInput v-model="replaceHtml" :disabled="applying" />
      <template #footer>
        <el-button @click="replaceHtmlVisible = false">取消</el-button>
        <el-button type="primary" :loading="applying" @click="submitReplaceHtml">替换</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 挂在沉浸布局(FocusLayout,无公共头)下,占满视口 */
.workbench {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f6f8;
}

.workbench-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 20px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.stage-title {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}

.link-text {
  padding: 0;
  border: 0;
  background: transparent;
  font: inherit;
  color: inherit;
  cursor: pointer;
}

.link-text:hover {
  color: var(--brand);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.workbench-loading {
  padding: 48px 0;
  text-align: center;
  color: var(--text-muted);
}

.workbench-body {
  flex: 1;
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) 340px;
  min-height: 0;
}

:global(.generation-drawer-body) {
  padding: 0;
}

.scene-rail {
  background: var(--surface);
  border-right: 1px solid var(--border);
  overflow-y: auto;
  min-height: 0;
}

.regenerate-traces {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-muted);
}

.stage-area {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}

.canvas-toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 6px 16px 0;
  flex: none;
}

.canvas-area {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 24px 16px;
  overflow: auto;
  min-height: 0;
}

.interactive-frame {
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 12px rgba(31, 35, 41, 0.12);
  background: #fff;
}

.edit-rail {
  background: var(--surface);
  border-left: 1px solid var(--border);
  overflow-y: auto;
  padding: 0 14px;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.edit-rail :deep(.el-tabs) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.edit-rail :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.rail-empty {
  margin: 12px 0;
  font-size: 12.5px;
  color: var(--text-muted);
}

</style>
