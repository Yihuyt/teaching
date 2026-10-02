/**
 * 画布吸附 —— 拖动 / 缩放时把移动中的矩形边与中线吸到其他帧、页面边与中线上的纯数学。
 * 坐标全部是 1280×720 逻辑像素;阈值也按逻辑像素给(调用方按画布缩放折算)。
 */

export interface Rect {
  x: number
  y: number
  w: number
  h: number
}

export interface SnapGuide {
  axis: 'x' | 'y'
  at: number
}

interface SnapResult {
  rect: Rect
  guides: SnapGuide[]
}

interface CanvasSize {
  width: number
  height: number
}

function lines(start: number, size: number): number[] {
  return [start, start + size / 2, start + size]
}

function candidates(targets: Rect[], scene: CanvasSize): { x: number[]; y: number[] } {
  const x = new Set<number>([0, scene.width / 2, scene.width])
  const y = new Set<number>([0, scene.height / 2, scene.height])
  for (const t of targets) {
    for (const v of lines(t.x, t.w)) x.add(v)
    for (const v of lines(t.y, t.h)) y.add(v)
  }
  return { x: [...x], y: [...y] }
}

function nearest(value: number, cand: number[], threshold: number): number | null {
  let best: number | null = null
  let bestDelta = threshold
  for (const line of cand) {
    const delta = Math.abs(line - value)
    if (delta <= bestDelta) {
      bestDelta = delta
      best = line
    }
  }
  return best
}

function snapAxis(start: number, size: number, cand: number[], threshold: number): { delta: number; at: number } | null {
  let best: { delta: number; at: number } | null = null
  for (const line of lines(start, size)) {
    const hit = nearest(line, cand, threshold)
    if (hit !== null && (best === null || Math.abs(hit - line) < Math.abs(best.delta))) {
      best = { delta: hit - line, at: hit }
    }
  }
  return best
}

export function snapMove(moving: Rect, targets: Rect[], scene: CanvasSize, threshold: number): SnapResult {
  const cand = candidates(targets, scene)
  const x = snapAxis(moving.x, moving.w, cand.x, threshold)
  const y = snapAxis(moving.y, moving.h, cand.y, threshold)
  const guides: SnapGuide[] = []
  if (x) guides.push({ axis: 'x', at: x.at })
  if (y) guides.push({ axis: 'y', at: y.at })
  return { rect: { ...moving, x: moving.x + (x?.delta ?? 0), y: moving.y + (y?.delta ?? 0) }, guides }
}

export function snapEdge(
  axis: 'x' | 'y',
  value: number,
  targets: Rect[],
  scene: CanvasSize,
  threshold: number,
): { value: number; guide: SnapGuide | null } {
  const cand = candidates(targets, scene)
  const hit = nearest(value, axis === 'x' ? cand.x : cand.y, threshold)
  return hit === null ? { value, guide: null } : { value: hit, guide: { axis, at: hit } }
}

export function clampToScene(rect: Rect, scene: CanvasSize): Rect {
  const x = Math.max(0, Math.min(rect.x, scene.width - rect.w))
  const y = Math.max(0, Math.min(rect.y, scene.height - rect.h))
  return { ...rect, x, y }
}
