import { describe, it, expect } from 'vitest'
import { snapMove, snapEdge, clampToScene } from '@/features/courseware/editor/snapping'

const scene = { width: 1280, height: 720 }

describe('snapMove', () => {
  it('左边靠近另一帧左边时整体平移对齐并给出竖向辅助线', () => {
    const result = snapMove({ x: 104, y: 300, w: 200, h: 100 }, [{ x: 100, y: 0, w: 300, h: 50 }], scene, 6)
    expect(result.rect.x).toBe(100)
    expect(result.rect.y).toBe(300)
    expect(result.guides).toContainEqual({ axis: 'x', at: 100 })
  })

  it('两轴独立:纵向中线吸到页面中线,横向不动', () => {
    const result = snapMove({ x: 500, y: 313, w: 200, h: 100 }, [], scene, 6)
    expect(result.rect.y).toBe(310)
    expect(result.rect.x).toBe(500)
    expect(result.guides).toEqual([{ axis: 'y', at: 360 }])
  })

  it('超出阈值不吸附、无辅助线', () => {
    const result = snapMove({ x: 120, y: 200, w: 200, h: 100 }, [{ x: 100, y: 0, w: 300, h: 50 }], scene, 6)
    expect(result.rect).toEqual({ x: 120, y: 200, w: 200, h: 100 })
    expect(result.guides).toEqual([])
  })
})

describe('snapEdge / clampToScene', () => {
  it('缩放只吸正在拖的那条边', () => {
    expect(snapEdge('x', 1276, [], scene, 6)).toEqual({ value: 1280, guide: { axis: 'x', at: 1280 } })
    expect(snapEdge('y', 100, [], scene, 6)).toEqual({ value: 100, guide: null })
  })

  it('矩形越界时推回页面内,尺寸不变', () => {
    expect(clampToScene({ x: -10, y: 700, w: 200, h: 100 }, scene)).toEqual({ x: 0, y: 620, w: 200, h: 100 })
  })
})
