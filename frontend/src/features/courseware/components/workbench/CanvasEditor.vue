<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, nextTick } from 'vue'
import type { Block, BlockLayout, EditOp, Scene } from '@/features/courseware/dsl'
import { FREE_HEIGHT_TYPES, MIN_PIN_HEIGHT, MIN_PIN_WIDTH } from '@/features/courseware/dsl'
import { layoutScene, DEFAULT_THEME } from '@/features/courseware/layout'
import type { Frame } from '@/features/courseware/layout'
import SceneCanvas from '@/features/courseware/render/SceneCanvas.vue'
import { clampToScene, snapEdge, snapMove } from '@/features/courseware/editor/snapping'
import type { Rect, SnapGuide } from '@/features/courseware/editor/snapping'

const props = defineProps<{
  scene: Scene
  width: number
  assetUrls: Readonly<Record<string, string>>
  selectedId: string | null
  busy: boolean
}>()
const emit = defineEmits<{ select: [blockId: string | null]; ops: [ops: EditOp[]] }>()

const t = DEFAULT_THEME
const PAGE = { width: t.canvas.width, height: t.canvas.height }
const SNAP_SCREEN_PX = 6
const DRAG_THRESHOLD_PX = 3

const scale = computed(() => props.width / PAGE.width)

const draftLayouts = ref<BlockLayout[] | null>(null)
const previewScene = computed<Scene>(() =>
  draftLayouts.value ? { ...props.scene, layouts: draftLayouts.value } : props.scene,
)
const positioned = computed(() => layoutScene(previewScene.value))

const topLevelIds = computed(() => new Set(props.scene.blocks.map((b) => b.id)))
const blockById = computed(() => new Map(props.scene.blocks.map((b) => [b.id, b] as const)))

/** 可点选的帧 = 顶层块的帧(columns 内子块不能单独覆盖,不可选) */
const selectableFrames = computed(() => positioned.value.frames.filter((f) => topLevelIds.value.has(f.blockId)))
const selectedFrame = computed(() => selectableFrames.value.find((f) => f.blockId === props.selectedId) ?? null)
const selectedBlock = computed(() => (props.selectedId ? (blockById.value.get(props.selectedId) ?? null) : null))

function layoutOf(blockId: string): BlockLayout | undefined {
  return props.scene.layouts?.find((l) => l.blockId === blockId)
}

function pinnable(block: Block | null): boolean {
  return block !== null && block.type !== 'columns'
}

function freeHeight(block: Block | null): boolean {
  return block !== null && FREE_HEIGHT_TYPES.has(block.type)
}

/* ---- 出页提醒:钉住的块底边伸出页面(文字块高度跟内容走,钉在下方时会出页) ---- */
const troubledIds = computed(() => {
  const ids = new Set<string>()
  for (const frame of positioned.value.frames) {
    if (frame.pinned && !frame.blockId.startsWith('__') && frame.y + frame.h > PAGE.height + 0.5) ids.add(frame.blockId)
  }
  return ids
})

const root = ref<HTMLElement | null>(null)

function frameStyle(f: { x: number; y: number; w: number; h: number }): Record<string, string> {
  return {
    left: `${f.x * scale.value}px`,
    top: `${f.y * scale.value}px`,
    width: `${f.w * scale.value}px`,
    height: `${f.h * scale.value}px`,
  }
}

function currentRect(blockId: string): Rect | null {
  const f = selectableFrames.value.find((x) => x.blockId === blockId)
  return f ? { x: f.x, y: f.y, w: f.w, h: f.h } : null
}

function otherRects(blockId: string): Rect[] {
  const rects: Rect[] = selectableFrames.value.filter((f) => f.blockId !== blockId).map((f) => ({ x: f.x, y: f.y, w: f.w, h: f.h }))
  const title = positioned.value.title
  if (title) rects.push({ x: title.x, y: title.y, w: title.w, h: title.h })
  return rects
}

function draftWith(blockId: string, rect: Rect): BlockLayout[] {
  const block = blockById.value.get(blockId)
  const frame = freeHeight(block ?? null) ? { x: rect.x, y: rect.y, w: rect.w, h: rect.h } : { x: rect.x, y: rect.y, w: rect.w }
  const rest = (props.scene.layouts ?? []).filter((l) => l.blockId !== blockId)
  const size = layoutOf(blockId)?.size
  return [...rest, { blockId, frame, ...(size ? { size } : {}) }]
}

function pinOp(blockId: string, rect: Rect): EditOp {
  const block = blockById.value.get(blockId)
  return {
    op: 'pin_block',
    sceneId: props.scene.id,
    blockId,
    x: Math.round(rect.x),
    y: Math.round(rect.y),
    w: Math.round(rect.w),
    ...(freeHeight(block ?? null) ? { h: Math.round(rect.h) } : {}),
  }
}

type Handle = 'n' | 's' | 'w' | 'e' | 'nw' | 'ne' | 'sw' | 'se'

interface Gesture {
  kind: 'move' | 'resize'
  blockId: string
  handle: Handle | null
  startClient: { x: number; y: number }
  origin: Rect
  ratio: number
  moved: boolean
}

const gesture = ref<Gesture | null>(null)
const guides = ref<SnapGuide[]>([])
const liveRect = ref<Rect | null>(null)

function onFramePointerDown(event: PointerEvent, blockId: string): void {
  if (props.busy || event.button !== 0) return
  if (inlineEdit.value) commitInline()
  emit('select', blockId)
  const block = blockById.value.get(blockId) ?? null
  const origin = currentRect(blockId)
  if (!pinnable(block) || !origin) return
  beginGesture(event, { kind: 'move', blockId, handle: null, origin })
}

function onHandlePointerDown(event: PointerEvent, handle: Handle): void {
  if (props.busy || event.button !== 0 || !props.selectedId) return
  const origin = currentRect(props.selectedId)
  if (!origin) return
  event.stopPropagation()
  beginGesture(event, { kind: 'resize', blockId: props.selectedId, handle, origin })
}

function beginGesture(event: PointerEvent, init: Pick<Gesture, 'kind' | 'blockId' | 'handle' | 'origin'>): void {
  gesture.value = {
    ...init,
    startClient: { x: event.clientX, y: event.clientY },
    ratio: init.origin.h > 0 ? init.origin.w / init.origin.h : 1,
    moved: false,
  }
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  event.preventDefault()
}

function onPointerMove(event: PointerEvent): void {
  const g = gesture.value
  if (!g) return
  const dxScreen = event.clientX - g.startClient.x
  const dyScreen = event.clientY - g.startClient.y
  if (!g.moved && Math.hypot(dxScreen, dyScreen) < DRAG_THRESHOLD_PX) return
  g.moved = true
  const dx = dxScreen / scale.value
  const dy = dyScreen / scale.value
  const threshold = SNAP_SCREEN_PX / scale.value
  const others = otherRects(g.blockId)

  let rect: Rect
  if (g.kind === 'move') {
    const snapped = snapMove({ ...g.origin, x: g.origin.x + dx, y: g.origin.y + dy }, others, PAGE, threshold)
    rect = clampToScene(snapped.rect, PAGE)
    guides.value = snapped.guides
  } else {
    const resized = resize(g, dx, dy, others, threshold)
    rect = resized.rect
    guides.value = resized.guides
  }
  liveRect.value = rect
  draftLayouts.value = draftWith(g.blockId, rect)
}

function resize(g: Gesture, dx: number, dy: number, others: Rect[], threshold: number): { rect: Rect; guides: SnapGuide[] } {
  const h = g.handle as Handle
  const o = g.origin
  const block = blockById.value.get(g.blockId) ?? null
  let left = o.x
  let right = o.x + o.w
  let top = o.y
  let bottom = o.y + o.h
  const hits: SnapGuide[] = []

  if (h.includes('w')) {
    const s = snapEdge('x', o.x + dx, others, PAGE, threshold)
    left = Math.max(0, Math.min(s.value, right - MIN_PIN_WIDTH))
    if (s.guide && left === s.value) hits.push(s.guide)
  }
  if (h.includes('e')) {
    const s = snapEdge('x', o.x + o.w + dx, others, PAGE, threshold)
    right = Math.min(PAGE.width, Math.max(s.value, left + MIN_PIN_WIDTH))
    if (s.guide && right === s.value) hits.push(s.guide)
  }
  if (freeHeight(block)) {
    if (h.includes('n')) {
      const s = snapEdge('y', o.y + dy, others, PAGE, threshold)
      top = Math.max(0, Math.min(s.value, bottom - MIN_PIN_HEIGHT))
      if (s.guide && top === s.value) hits.push(s.guide)
    }
    if (h.includes('s')) {
      const s = snapEdge('y', o.y + o.h + dy, others, PAGE, threshold)
      bottom = Math.min(PAGE.height, Math.max(s.value, top + MIN_PIN_HEIGHT))
      if (s.guide && bottom === s.value) hits.push(s.guide)
    }
    if (block?.type === 'image' && h.length === 2) {
      const w = right - left
      const newH = Math.max(MIN_PIN_HEIGHT, w / g.ratio)
      if (h.includes('n')) top = Math.max(0, bottom - newH)
      else bottom = Math.min(PAGE.height, top + newH)
      hits.length = 0
    }
  }
  return { rect: { x: left, y: top, w: right - left, h: bottom - top }, guides: hits }
}

function onPointerUp(): void {
  const g = gesture.value
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  gesture.value = null
  guides.value = []
  const rect = liveRect.value
  liveRect.value = null
  draftLayouts.value = null
  if (g && g.moved && rect) emit('ops', [pinOp(g.blockId, rect)])
}

/* ---- 键盘:方向键微移、Delete 删块(Backspace 不接管,误触太常见)、Esc 取消选中 ---- */
function isTypingTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

function onKeyDown(event: KeyboardEvent): void {
  if (props.busy || !props.selectedId || isTypingTarget(event.target) || inlineEdit.value) return
  if (event.key === 'Escape') {
    emit('select', null)
    return
  }
  if (event.key === 'Delete') {
    event.preventDefault()
    emit('ops', [{ op: 'delete_block', sceneId: props.scene.id, blockId: props.selectedId }])
    return
  }
  const step = event.shiftKey ? 10 : 1
  const delta: Record<string, [number, number]> = {
    ArrowLeft: [-step, 0],
    ArrowRight: [step, 0],
    ArrowUp: [0, -step],
    ArrowDown: [0, step],
  }
  const move = delta[event.key]
  if (!move || !pinnable(selectedBlock.value)) return
  const origin = currentRect(props.selectedId)
  if (!origin) return
  event.preventDefault()
  const rect = clampToScene({ ...origin, x: origin.x + move[0], y: origin.y + move[1] }, PAGE)
  emit('ops', [pinOp(props.selectedId, rect)])
}

onMounted(() => window.addEventListener('keydown', onKeyDown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
})

interface InlineEdit {
  blockId: string
  /** bullets 的条目序号(0 起);其他块为 null */
  item: number | null
  text: string
  style: Record<string, string>
}

const inlineEdit = ref<InlineEdit | null>(null)
const inlineInput = ref<HTMLTextAreaElement | null>(null)

function inlineFontPx(block: Block, fontScale: number): number {
  switch (block.type) {
    case 'heading':
      return (block.level === 1 ? t.fontSize.h1 : t.fontSize.h2) * fontScale
    case 'emphasis':
      return t.fontSize.h1 * fontScale
    default:
      return t.fontSize.body * fontScale
  }
}

function onFrameDoubleClick(event: MouseEvent, frame: Frame): void {
  if (props.busy) return
  const block = blockById.value.get(frame.blockId)
  if (!block) return
  let item: number | null = null
  let text: string
  let box: Rect = { x: frame.x, y: frame.y, w: frame.w, h: frame.h }
  if (block.type === 'bullets') {
    // 手势层盖在内容之上,按指针位置找画布里对应的条目元素
    const under = document
      .elementsFromPoint(event.clientX, event.clientY)
      .find((el) => (el as HTMLElement).dataset?.itemIndex !== undefined) as HTMLElement | undefined
    if (!under) return
    item = Number(under.dataset.itemIndex) - 1
    const entry = block.items[item]
    if (!entry) return
    text = entry.text
    const rootBox = root.value!.getBoundingClientRect()
    const itemBox = under.getBoundingClientRect()
    box = {
      x: (itemBox.left - rootBox.left) / scale.value,
      y: (itemBox.top - rootBox.top) / scale.value,
      w: itemBox.width / scale.value,
      h: Math.max(itemBox.height / scale.value, t.fontSize.body * frame.fontScale * t.lineHeight),
    }
  } else if (block.type === 'heading' || block.type === 'paragraph' || block.type === 'emphasis') {
    text = block.text
  } else {
    return
  }
  inlineEdit.value = {
    blockId: block.id,
    item,
    text,
    style: {
      ...frameStyle(box),
      fontSize: `${inlineFontPx(block, frame.fontScale) * scale.value}px`,
      lineHeight: String(t.lineHeight),
      fontWeight: block.type === 'heading' || block.type === 'emphasis' ? '700' : '400',
      textAlign: block.type === 'emphasis' ? 'center' : 'left',
      color: block.type === 'emphasis' ? t.colors.primary : t.colors.text,
      fontFamily: t.fontFamily.body,
    },
  }
  void nextTick(() => {
    inlineInput.value?.focus()
    inlineInput.value?.select()
  })
}

function commitInline(): void {
  const edit = inlineEdit.value
  inlineEdit.value = null
  if (!edit) return
  const block = blockById.value.get(edit.blockId)
  if (!block) return
  const text = edit.text.replace(/\r?\n/g, ' ').trim()
  if (text === '') return
  let next: Block
  if (block.type === 'bullets' && edit.item !== null) {
    if (block.items[edit.item]?.text === text) return
    next = { ...block, items: block.items.map((it, i) => (i === edit.item ? { ...it, text } : it)) }
  } else if (block.type === 'heading' || block.type === 'paragraph' || block.type === 'emphasis') {
    if (block.text === text) return
    next = { ...block, text }
  } else {
    return
  }
  emit('ops', [{ op: 'replace_block', sceneId: props.scene.id, blockId: block.id, block: next }])
}

function onInlineKey(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    commitInline()
  } else if (event.key === 'Escape') {
    inlineEdit.value = null
  }
}

watch(() => props.scene.id, () => {
  inlineEdit.value = null
})

const HANDLES_TEXT: Handle[] = ['w', 'e']
const HANDLES_FREE: Handle[] = ['n', 's', 'w', 'e', 'nw', 'ne', 'sw', 'se']
const handles = computed<Handle[]>(() => {
  const block = selectedBlock.value
  if (!pinnable(block)) return []
  return freeHeight(block) ? HANDLES_FREE : HANDLES_TEXT
})

const CURSORS: Record<Handle, string> = {
  n: 'ns-resize',
  s: 'ns-resize',
  w: 'ew-resize',
  e: 'ew-resize',
  nw: 'nwse-resize',
  se: 'nwse-resize',
  ne: 'nesw-resize',
  sw: 'nesw-resize',
}

function handleStyle(frame: Rect, h: Handle): Record<string, string> {
  const x = h.includes('w') ? frame.x : h.includes('e') ? frame.x + frame.w : frame.x + frame.w / 2
  const y = h.includes('n') ? frame.y : h.includes('s') ? frame.y + frame.h : frame.y + frame.h / 2
  return { left: `${x * scale.value}px`, top: `${y * scale.value}px`, cursor: CURSORS[h] }
}

const statusText = computed(() => (positioned.value.overflow === 'error' ? '内容超出版面' : ''))
</script>

<template>
  <div class="canvas-editor" :style="{ width: `${width}px` }">
    <div ref="root" class="editor-stage" :style="{ width: `${width}px`, height: `${(width * PAGE.height) / PAGE.width}px` }">
      <SceneCanvas :scene="previewScene" :width="width" :asset-urls="assetUrls" />

      <div class="hit-layer" @pointerdown.self="emit('select', null)">
        <div
          v-for="frame in selectableFrames"
          :key="frame.blockId"
          class="hit-frame"
          :class="{
            selected: frame.blockId === selectedId,
            pinned: frame.pinned,
            troubled: troubledIds.has(frame.blockId),
            dragging: gesture?.blockId === frame.blockId,
          }"
          :style="frameStyle(frame)"
          :data-hit-block="frame.blockId"
          @pointerdown="onFramePointerDown($event, frame.blockId)"
          @dblclick="onFrameDoubleClick($event, frame)"
        >
          <span v-if="frame.pinned" class="pin-badge" title="已钉住">已钉住</span>
        </div>

        <template v-if="selectedFrame && !busy">
          <span
            v-for="h in handles"
            :key="h"
            class="handle"
            :class="`handle-${h}`"
            :style="handleStyle(selectedFrame, h)"
            @pointerdown="onHandlePointerDown($event, h)"
          />
        </template>

        <div
          v-for="(g, i) in guides"
          :key="i"
          class="guide"
          :style="
            g.axis === 'x'
              ? { left: `${g.at * scale}px`, top: '0', width: '1px', height: '100%' }
              : { top: `${g.at * scale}px`, left: '0', height: '1px', width: '100%' }
          "
        />

        <textarea
          v-if="inlineEdit"
          ref="inlineInput"
          v-model="inlineEdit.text"
          class="inline-input"
          :style="inlineEdit.style"
          @blur="commitInline"
          @keydown="onInlineKey"
        />
      </div>
    </div>
    <p v-if="statusText" class="editor-status warn">{{ statusText }}</p>
  </div>
</template>

<style scoped>
.canvas-editor {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.editor-stage {
  position: relative;
}

.hit-layer {
  position: absolute;
  inset: 0;
  user-select: none;
}

.hit-frame {
  position: absolute;
  box-sizing: border-box;
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: grab;
}

.hit-frame:hover {
  border-color: rgba(37, 99, 235, 0.35);
}

.hit-frame.selected {
  border-color: var(--brand, #2563eb);
  box-shadow: 0 0 0 1px rgba(37, 99, 235, 0.25);
}

.hit-frame.troubled {
  border-color: #dc2626;
  box-shadow: 0 0 0 1px rgba(220, 38, 38, 0.35);
}

.hit-frame.dragging {
  cursor: grabbing;
}

.pin-badge {
  position: absolute;
  right: 4px;
  top: 4px;
  padding: 0 6px;
  border-radius: 3px;
  background: rgba(37, 99, 235, 0.85);
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  pointer-events: none;
}

.handle {
  position: absolute;
  width: 10px;
  height: 10px;
  margin: -5px 0 0 -5px;
  border: 1px solid var(--brand, #2563eb);
  border-radius: 2px;
  background: #fff;
  box-sizing: border-box;
  z-index: 2;
}

.guide {
  position: absolute;
  background: #f59e0b;
  pointer-events: none;
  z-index: 3;
}

.inline-input {
  position: absolute;
  box-sizing: border-box;
  margin: 0;
  padding: 0;
  border: 1px solid var(--brand, #2563eb);
  border-radius: 2px;
  background: #fff;
  resize: none;
  outline: none;
  overflow: hidden;
  z-index: 4;
}

.editor-status {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-muted);
}

.editor-status.warn {
  color: #dc2626;
}
</style>
