import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

interface Size {
  width: number
  height: number
}

export interface Rect extends Size {
  x: number
  y: number
}

interface FloatState extends Rect {
  collapsed: boolean
  maximized: boolean
}

type ResizeEdge = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw'
export const RESIZE_EDGES: readonly ResizeEdge[] = ['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw']

export const MIN_WIDTH = 320
export const MIN_HEIGHT = 360
export const MARGIN = 12
export const COLLAPSED_HEIGHT = 44
const DEFAULT_WIDTH = 420
const DEFAULT_HEIGHT = 640

export function clampRect(rect: Rect, container: Size): Rect {
  const maxWidth = Math.max(MIN_WIDTH, container.width - MARGIN * 2)
  const maxHeight = Math.max(MIN_HEIGHT, container.height - MARGIN * 2)
  const width = Math.min(maxWidth, Math.max(MIN_WIDTH, Math.round(rect.width)))
  const height = Math.min(maxHeight, Math.max(MIN_HEIGHT, Math.round(rect.height)))
  const x = Math.min(Math.max(MARGIN, container.width - width - MARGIN), Math.max(MARGIN, Math.round(rect.x)))
  const y = Math.min(Math.max(MARGIN, container.height - height - MARGIN), Math.max(MARGIN, Math.round(rect.y)))
  return { x, y, width, height }
}

/**
 * 按拖动的边算新矩形:拉右边或下边只改宽高;拉左边或上边要连位置一起动,而且对面那条边钉住不动——
 * 缩到下限或顶到容器边距时,是这条边停下,不是对面那条边跟着走。
 */
export function resizeRect(origin: Rect, edge: ResizeEdge, dx: number, dy: number, container: Size): Rect {
  const maxWidth = Math.max(MIN_WIDTH, container.width - MARGIN * 2)
  const maxHeight = Math.max(MIN_HEIGHT, container.height - MARGIN * 2)
  let { x, y, width, height } = origin
  if (edge.includes('e')) width = Math.min(origin.width + dx, container.width - MARGIN - origin.x)
  if (edge.includes('s')) height = Math.min(origin.height + dy, container.height - MARGIN - origin.y)
  if (edge.includes('w')) {
    const right = origin.x + origin.width
    x = Math.min(Math.max(MARGIN, origin.x + dx), right - MIN_WIDTH)
    width = right - x
  }
  if (edge.includes('n')) {
    const bottom = origin.y + origin.height
    y = Math.min(Math.max(MARGIN, origin.y + dy), bottom - MIN_HEIGHT)
    height = bottom - y
  }
  return clampRect({ x, y, width: Math.min(width, maxWidth), height: Math.min(height, maxHeight) }, container)
}

export function defaultRect(container: Size): Rect {
  const width = Math.min(DEFAULT_WIDTH, Math.max(MIN_WIDTH, container.width - MARGIN * 2))
  const height = Math.min(DEFAULT_HEIGHT, Math.max(MIN_HEIGHT, container.height - MARGIN * 2))
  return clampRect({ x: container.width - width - MARGIN, y: container.height - height - MARGIN, width, height }, container)
}

export function readState(storageKey: string, storage: Pick<Storage, 'getItem'> | null): FloatState | null {
  try {
    const raw = storage?.getItem(storageKey)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return null
    const value = parsed as Record<string, unknown>
    const numbers = [value.x, value.y, value.width, value.height]
    if (!numbers.every((n) => typeof n === 'number' && Number.isFinite(n))) return null
    return {
      x: value.x as number,
      y: value.y as number,
      width: value.width as number,
      height: value.height as number,
      collapsed: value.collapsed === true,
      maximized: value.maximized === true,
    }
  } catch {
    return null
  }
}

export function writeState(storageKey: string, storage: Pick<Storage, 'setItem'> | null, state: FloatState): void {
  try {
    storage?.setItem(storageKey, JSON.stringify(state))
  } catch {
    // 存不下就下次再默认
  }
}

/**
 * 悬浮面板:container 是它浮在里面的元素(position 非 static)。
 * 返回的 style 直接绑到面板上;startDrag 接到标题栏的 pointerdown,startResize 接到各条边、各个角的 pointerdown;
 * moving 为真时调用方要让下面的 iframe 不接收指针(iframe 会吞掉拖动中的事件)。
 */
export function useFloatingPanel(container: Ref<HTMLElement | null>, storageKey: string) {
  const state = ref<FloatState>({ x: MARGIN, y: MARGIN, width: DEFAULT_WIDTH, height: DEFAULT_HEIGHT, collapsed: false, maximized: false })
  const moving = ref(false)
  let initialized = false

  function containerSize(): Size {
    const el = container.value
    return { width: el?.clientWidth ?? 0, height: el?.clientHeight ?? 0 }
  }

  function persist(): void {
    writeState(storageKey, typeof window === 'undefined' ? null : window.localStorage, state.value)
  }

  function fit(): void {
    const size = containerSize()
    if (size.width === 0 || size.height === 0) return
    if (!initialized) {
      initialized = true
      const stored = readState(storageKey, typeof window === 'undefined' ? null : window.localStorage)
      if (stored) {
        state.value = { ...stored, ...clampRect(stored, size) }
        return
      }
      state.value = { ...defaultRect(size), collapsed: false, maximized: false }
      return
    }
    state.value = { ...state.value, ...clampRect(state.value, size) }
  }

  const style = computed<Record<string, string>>(() => {
    if (state.value.maximized) {
      return { left: `${MARGIN}px`, top: `${MARGIN}px`, width: `calc(100% - ${MARGIN * 2}px)`, height: `calc(100% - ${MARGIN * 2}px)` }
    }
    return {
      left: `${state.value.x}px`,
      top: `${state.value.y}px`,
      width: `${state.value.width}px`,
      height: state.value.collapsed ? `${COLLAPSED_HEIGHT}px` : `${state.value.height}px`,
    }
  })

  function track(event: PointerEvent, onMove: (dx: number, dy: number) => void): void {
    event.preventDefault()
    moving.value = true
    const startX = event.clientX
    const startY = event.clientY
    const move = (e: PointerEvent): void => onMove(e.clientX - startX, e.clientY - startY)
    const stop = (): void => {
      moving.value = false
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', stop)
      window.removeEventListener('pointercancel', stop)
      persist()
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', stop)
    window.addEventListener('pointercancel', stop)
  }

  function startDrag(event: PointerEvent): void {
    if (state.value.maximized) return
    const origin = { ...state.value }
    track(event, (dx, dy) => {
      const size = containerSize()
      const rect = state.value.collapsed
        ? clampRect({ ...origin, x: origin.x + dx, y: origin.y + dy, height: COLLAPSED_HEIGHT }, size)
        : clampRect({ ...origin, x: origin.x + dx, y: origin.y + dy }, size)
      state.value = { ...state.value, x: rect.x, y: rect.y }
    })
  }

  function startResize(event: PointerEvent, edge: ResizeEdge): void {
    if (state.value.maximized || state.value.collapsed) return
    const origin = { ...state.value }
    track(event, (dx, dy) => {
      state.value = { ...state.value, ...resizeRect(origin, edge, dx, dy, containerSize()) }
    })
  }

  function toggleCollapsed(): void {
    state.value = { ...state.value, collapsed: !state.value.collapsed, maximized: false }
    persist()
  }

  function toggleMaximized(): void {
    state.value = { ...state.value, maximized: !state.value.maximized, collapsed: false }
    persist()
  }

  onMounted(() => {
    fit()
    window.addEventListener('resize', fit)
  })
  onBeforeUnmount(() => {
    window.removeEventListener('resize', fit)
  })

  return { state, style, moving, startDrag, startResize, toggleCollapsed, toggleMaximized, fit }
}
