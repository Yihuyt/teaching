import { describe, expect, it } from 'vitest'

import { COLLAPSED_HEIGHT, MARGIN, MIN_HEIGHT, MIN_WIDTH, clampRect, defaultRect, readState, resizeRect, writeState } from '@/features/blockcoding/floatingPanel'

const container = { width: 1400, height: 900 }

describe('悬浮面板的几何', () => {
  it('夹进容器:太大就缩到容器减边距,太小就放大到下限,位置整块留在容器里', () => {
    expect(clampRect({ x: 2000, y: -50, width: 5000, height: 100 }, container)).toEqual({
      x: container.width - (container.width - MARGIN * 2) - MARGIN,
      y: MARGIN,
      width: container.width - MARGIN * 2,
      height: MIN_HEIGHT,
    })
    expect(clampRect({ x: 100, y: 100, width: 10, height: 10 }, container)).toEqual({ x: 100, y: 100, width: MIN_WIDTH, height: MIN_HEIGHT })
    expect(clampRect({ x: 1300, y: 800, width: 400, height: 500 }, container)).toEqual({
      x: container.width - 400 - MARGIN,
      y: container.height - 500 - MARGIN,
      width: 400,
      height: 500,
    })
  })

  it('默认贴右下角;容器很小时面板跟着缩', () => {
    expect(defaultRect(container)).toEqual({ x: container.width - 420 - MARGIN, y: container.height - 640 - MARGIN, width: 420, height: 640 })
    const small = defaultRect({ width: 400, height: 420 })
    expect(small.width).toBe(400 - MARGIN * 2)
    expect(small.height).toBe(420 - MARGIN * 2)
    expect(small.x).toBe(MARGIN)
    expect(small.y).toBe(MARGIN)
  })

  it('存取:写进去能原样读回;形状不对或读不到返回 null', () => {
    const store = new Map<string, string>()
    const storage = { getItem: (key: string) => store.get(key) ?? null, setItem: (key: string, value: string) => void store.set(key, value) }
    const state = { x: 40, y: 50, width: 480, height: 620, collapsed: true, maximized: false }
    writeState('k', storage, state)
    expect(readState('k', storage)).toEqual(state)
    expect(readState('missing', storage)).toBeNull()
    store.set('bad', '{"x":"a"}')
    expect(readState('bad', storage)).toBeNull()
    store.set('junk', 'not json')
    expect(readState('junk', storage)).toBeNull()
    expect(readState('k', null)).toBeNull()
  })

  it('拉右边、下边只改宽高;拉左边、上边连位置一起动,对面那条边钉住', () => {
    const origin = { x: 500, y: 300, width: 420, height: 500 }
    expect(resizeRect(origin, 'e', 60, 999, container)).toEqual({ ...origin, width: 480 })
    expect(resizeRect(origin, 's', 999, 40, container)).toEqual({ ...origin, height: 540 })
    expect(resizeRect(origin, 'w', -60, 0, container)).toEqual({ ...origin, x: 440, width: 480 })
    expect(resizeRect(origin, 'n', 0, -40, container)).toEqual({ ...origin, y: 260, height: 540 })
    expect(resizeRect(origin, 'nw', -60, -40, container)).toEqual({ x: 440, y: 260, width: 480, height: 540 })
    expect(resizeRect(origin, 'se', 60, 40, container)).toEqual({ ...origin, width: 480, height: 540 })
  })

  it('缩过下限时对面那条边不动;拉到容器边距外时这条边停在边距上', () => {
    const origin = { x: 500, y: 300, width: 420, height: 500 }
    expect(resizeRect(origin, 'w', 400, 0, container)).toEqual({ ...origin, x: 500 + 420 - MIN_WIDTH, width: MIN_WIDTH })
    expect(resizeRect(origin, 'n', 0, 400, container)).toEqual({ ...origin, y: 300 + 500 - MIN_HEIGHT, height: MIN_HEIGHT })
    expect(resizeRect(origin, 'w', -900, 0, container)).toEqual({ ...origin, x: MARGIN, width: 500 + 420 - MARGIN })
    expect(resizeRect(origin, 'e', 9999, 0, container)).toEqual({ ...origin, width: container.width - MARGIN - 500 })
    expect(resizeRect(origin, 's', 0, 9999, container)).toEqual({ ...origin, height: container.height - MARGIN - 300 })
  })

  it('收起高度只剩标题栏', () => {
    expect(COLLAPSED_HEIGHT).toBeLessThan(MIN_HEIGHT)
  })
})
