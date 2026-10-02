<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

import { api, errorMessage } from '@/api/client'
import { RESIZE_EDGES, useFloatingPanel } from '@/features/blockcoding/floatingPanel'
import ChatPanel from '@/features/blockcoding/components/ChatPanel.vue'
import EditorFrame from '@/features/blockcoding/components/EditorFrame.vue'
import StudioToolbar from '@/features/blockcoding/components/StudioToolbar.vue'
import { ScratchBridge } from '@/features/blockcoding/scratchBridge'
import { useBlockCodingStore } from '@/features/blockcoding/store'

const AUTOSAVE_DELAY_MS = 60_000
const PANEL_STATE_KEY = 'blockcoding.chatPanel'

const route = useRoute()
const store = useBlockCodingStore()
const bridge = new ScratchBridge()

const courseId = computed(() => Number(route.params.courseId))
const projectId = computed(() => Number(route.params.projectId))
const editorReady = ref(false)
const editorError = ref('')
/** 作品文件的装载状态:没装好之前不能保存,不然会把编辑器的默认空作品盖到真实文件上 */
const fileState = ref<'loading' | 'ready' | 'failed'>('loading')
/** 作品元数据的读取:编辑器可能先于它就绪(iframe 有缓存),装文件前要等它 */
let projectLoaded: Promise<void> = Promise.resolve()
const bodyEl = ref<HTMLElement | null>(null)
const panelEl = ref<HTMLElement | null>(null)
const editorFrameEl = () => bodyEl.value?.querySelector<HTMLIFrameElement>('iframe.editor-frame') ?? null
const { state: panelState, style: panelStyle, moving: panelMoving, startDrag, startResize, toggleCollapsed, toggleMaximized } = useFloatingPanel(bodyEl, PANEL_STATE_KEY)

function syncPanelOverlay(): void {
  const frame = editorFrameEl()
  if (!editorReady.value || !frame || !panelEl.value) return
  const frameRect = frame.getBoundingClientRect()
  const rect = panelEl.value.getBoundingClientRect()
  void bridge
    .setHostOverlay({ x: rect.left - frameRect.left, y: rect.top - frameRect.top, width: rect.width, height: rect.height })
    .catch(() => undefined)
}
watch(panelStyle, () => void nextTick(syncPanelOverlay))
let autosaveTimer: number | null = null
// 装载 sb3 会触发编辑器的 PROJECT_CHANGED(桥有 2s 节流):装载期间和装载后的短窗口内不把它当作用户修改
let suppressDirtyUntil = 0

async function handleEditorReady(): Promise<void> {
  editorReady.value = true
  await nextTick()
  syncPanelOverlay()
  try {
    await projectLoaded
  } catch {
    fileState.value = 'failed'
    return
  }
  if (store.project?.hasFile) {
    suppressDirtyUntil = Number.MAX_SAFE_INTEGER
    try {
      // 实例级 Accept 是 application/json，这个二进制端点必须覆盖，否则 406
      const response = await api.blockCodingProjectLoadFile(courseId.value, projectId.value, {
        responseType: 'arraybuffer',
        headers: { Accept: 'application/octet-stream' },
      })
      await bridge.loadSb3(response.data as unknown as ArrayBuffer)
      suppressDirtyUntil = Date.now() + 3000
      store.saveState = 'saved'
    } catch (error) {
      fileState.value = 'failed'
      ElMessage.error(`载入作品失败：${errorMessage(error)}。刷新页面重试;在此之前不能保存`)
      return
    }
  }
  fileState.value = 'ready'
}

function handleEditorFailed(message: string): void {
  editorError.value = message
}

function handleDirty(): void {
  if (fileState.value !== 'ready' || Date.now() < suppressDirtyUntil) {
    return
  }
  store.markDirty()
  if (autosaveTimer === null) {
    autosaveTimer = window.setTimeout(() => {
      autosaveTimer = null
      void save()
    }, AUTOSAVE_DELAY_MS)
  }
}

async function save(): Promise<void> {
  if (store.saveState === 'saving') {
    return
  }
  if (fileState.value !== 'ready') {
    ElMessage.error(fileState.value === 'failed' ? '作品没有载入成功，不能保存，请刷新页面重试' : '作品还在载入，稍等再保存')
    return
  }
  store.saveState = 'saving'
  try {
    const buffer = await bridge.exportSb3()
    // axios 对 Blob 体不设 Content-Type，走默认 urlencoded 会毁掉二进制——必须显式
    const response = await api.blockCodingProjectSaveFile(
      courseId.value,
      projectId.value,
      new Blob([buffer], { type: 'application/octet-stream' }),
      { headers: { 'Content-Type': 'application/octet-stream' } },
    )
    store.project = response.data
    store.saveState = 'saved'
  } catch (error) {
    store.saveState = 'dirty'
    ElMessage.error(`保存失败：${errorMessage(error)}`)
  }
}

function beforeUnload(event: BeforeUnloadEvent): void {
  if (store.saveState !== 'saved') {
    event.preventDefault()
  }
}

onMounted(async () => {
  store.reset()
  store.management = route.name === 'admin-blockcoding-studio'
  store.courseId = courseId.value
  window.addEventListener('beforeunload', beforeUnload)
  window.addEventListener('resize', syncPanelOverlay)
  projectLoaded = store.loadProject(projectId.value)
  try {
    await projectLoaded
    await store.loadSession(projectId.value)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  window.removeEventListener('resize', syncPanelOverlay)
  if (autosaveTimer !== null) {
    window.clearTimeout(autosaveTimer)
  }
})
</script>

<template>
  <div class="studio">
    <StudioToolbar
      :project-name="store.project?.name ?? '…'"
      :save-state="store.saveState"
      :back="{ path: store.management ? `/admin/courses/${courseId}` : `/courses/${courseId}`, query: { tab: 'blockcoding' } }"
      :back-label="store.management ? '← 课程管理' : '← 返回课程'"
      @save="save"
    />
    <div ref="bodyEl" class="studio__body" :class="{ 'studio__body--moving': panelMoving }">
      <div class="studio__editor">
        <EditorFrame :bridge="bridge" @ready="handleEditorReady" @dirty="handleDirty" @failed="handleEditorFailed" />
        <div v-if="!editorReady" class="studio__loading">
          <template v-if="editorError">积木编辑器加载失败：{{ editorError }}。请刷新页面重试</template>
          <template v-else>
            <el-icon class="is-loading"><i /></el-icon>
            正在加载积木编辑器…
          </template>
        </div>
      </div>
      <div ref="panelEl" class="studio__panel" :class="{ 'studio__panel--collapsed': panelState.collapsed }" :style="panelStyle">
        <ChatPanel :project-id="projectId" :bridge="bridge" @grab="startDrag">
          <template #header-actions>
            <el-button size="small" text :title="panelState.collapsed ? '展开' : '收起'" @click="toggleCollapsed">
              {{ panelState.collapsed ? '展开' : '收起' }}
            </el-button>
            <el-button size="small" text :title="panelState.maximized ? '还原' : '最大化'" @click="toggleMaximized">
              {{ panelState.maximized ? '还原' : '最大化' }}
            </el-button>
          </template>
        </ChatPanel>
        <template v-if="!panelState.collapsed && !panelState.maximized">
          <div
            v-for="edge in RESIZE_EDGES"
            :key="edge"
            class="studio__panel-resize"
            :class="`studio__panel-resize--${edge}`"
            @pointerdown="startResize($event, edge)"
          />
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.studio {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 64px);
  min-width: 1200px;
  background: #fff;
}

.studio__body {
  position: relative;
  display: flex;
  flex: 1;
  min-height: 0;
}

.studio__editor {
  position: relative;
  flex: 1;
  min-width: 0;
}

.studio__body--moving {
  user-select: none;
}

/* 拖动 / 拉大小时 iframe 会吞掉指针事件:让它暂时不接收 */
.studio__body--moving .studio__editor {
  pointer-events: none;
}

.studio__panel {
  position: absolute;
  z-index: 5;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.16);
}

.studio__panel--collapsed {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}

/* 拉大小的把手:四条边各一条窄带,四个角各一小块(角压在边上面,先命中角);右下角画出斜纹提示 */
.studio__panel-resize {
  position: absolute;
  z-index: 1;
}

.studio__panel-resize--n,
.studio__panel-resize--s {
  right: 14px;
  left: 14px;
  height: 6px;
  cursor: ns-resize;
}

.studio__panel-resize--e,
.studio__panel-resize--w {
  top: 14px;
  bottom: 14px;
  width: 6px;
  cursor: ew-resize;
}

.studio__panel-resize--n { top: 0; }
.studio__panel-resize--s { bottom: 0; }
.studio__panel-resize--e { right: 0; }
.studio__panel-resize--w { left: 0; }

.studio__panel-resize--ne,
.studio__panel-resize--nw,
.studio__panel-resize--se,
.studio__panel-resize--sw {
  z-index: 2;
  width: 14px;
  height: 14px;
}

.studio__panel-resize--ne { top: 0; right: 0; cursor: nesw-resize; }
.studio__panel-resize--nw { top: 0; left: 0; cursor: nwse-resize; }
.studio__panel-resize--se { right: 0; bottom: 0; width: 18px; height: 18px; cursor: nwse-resize; }
.studio__panel-resize--sw { bottom: 0; left: 0; cursor: nesw-resize; }

.studio__panel-resize--se {
  background: linear-gradient(135deg, transparent 50%, var(--el-border-color-darker) 50%, var(--el-border-color-darker) 60%, transparent 60%, transparent 75%, var(--el-border-color-darker) 75%, var(--el-border-color-darker) 85%, transparent 85%);
}

.studio__loading {
  position: absolute;
  inset: 0;
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
  background: #fff;
}
</style>
