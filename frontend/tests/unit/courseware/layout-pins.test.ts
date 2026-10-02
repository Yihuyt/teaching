import { describe, it, expect } from 'vitest'
import type { Block, Scene } from '@/features/courseware/dsl'
import { layoutScene, describeOverflow, DEFAULT_THEME } from '@/features/courseware/layout'

const heading = (id: string, text: string): Block => ({ id, type: 'heading', level: 2, text })
const bullets = (id: string, texts: string[]): Block => ({
  id,
  type: 'bullets',
  items: texts.map((text) => ({ text })),
})
const image = (id: string): Block => ({ id, type: 'image', src: 'courseware/1/images/a.png', width: 800, height: 600 })

function makeScene(partial: Partial<Scene> & Pick<Scene, 'blocks'>): Scene {
  return {
    id: 'scene-1',
    type: 'content',
    title: '牛顿第二定律',
    preset: 'standard',
    speech: [],
    ...partial,
  }
}

const longText =
  '这是一条相当长的要点内容,用来占据版面空间以测试布局引擎的溢出处理逻辑是否正确工作,' +
  '再补充一句让它在标准内容区宽度下无论如何都需要换行排成两行的额外说明文字以便标定阶梯。'

describe('layoutScene — 钉住块', () => {
  it('钉住的图片按覆盖成帧并排在流式帧之后;其余块按原顺序流式排', () => {
    const scene = makeScene({
      blocks: [heading('blk-heading-1', '要点'), image('blk-image-1'), bullets('blk-bullets-1', ['甲', '乙'])],
      layouts: [{ blockId: 'blk-image-1', frame: { x: 800, y: 400, w: 400, h: 240 } }],
    })
    const result = layoutScene(scene)
    expect(result.frames.map((f) => f.blockId)).toEqual(['blk-heading-1', 'blk-bullets-1', 'blk-image-1'])
    const img = result.frames.find((f) => f.blockId === 'blk-image-1')!
    expect(img).toMatchObject({ x: 800, y: 400, w: 400, h: 240, pinned: true, fontScale: 1 })
    expect(result.frames.find((f) => f.blockId === 'blk-heading-1')!.pinned).toBe(false)
    expect(result.overflow).toBe('none')
  })

  it('文字类块钉住只取位置与宽度,高度按内容在该宽度下量出', () => {
    const scene = makeScene({
      blocks: [bullets('blk-bullets-1', [longText, longText])],
      layouts: [{ blockId: 'blk-bullets-1', frame: { x: 100, y: 200, w: 500 } }],
    })
    const narrow = layoutScene(scene).frames[0]!
    const wide = layoutScene({
      ...scene,
      layouts: [{ blockId: 'blk-bullets-1', frame: { x: 100, y: 200, w: 1100 } }],
    }).frames[0]!
    expect(narrow.w).toBe(500)
    expect(narrow.h).toBeGreaterThan(wide.h)
  })

  it('上下环绕:流式块跳过钉住块占的纵向区间,并保持一份块间距', () => {
    const scene = makeScene({
      blocks: [heading('blk-heading-1', '要点'), bullets('blk-bullets-1', ['甲', '乙', '丙']), image('blk-image-1')],
      layouts: [{ blockId: 'blk-image-1', frame: { x: 60, y: 260, w: 400, h: 120 } }],
    })
    const result = layoutScene(scene)
    const gap = DEFAULT_THEME.spacing.md
    const headingFrame = result.frames.find((f) => f.blockId === 'blk-heading-1')!
    const listFrame = result.frames.find((f) => f.blockId === 'blk-bullets-1')!
    // 标题从内容区顶(150)开始且在钉住块上方放得下;列表原本紧随其后,但与钉住块相交,被顶到 260+120+gap
    expect(headingFrame.y).toBe(150)
    expect(headingFrame.y + headingFrame.h + gap).toBeLessThan(260)
    expect(listFrame.y).toBe(260 + 120 + gap)
    expect(result.overflow).toBe('none')
  })

  it('钉住块在区域横向之外(不相交)时不影响流式排布', () => {
    const base = makeScene({ blocks: [heading('blk-heading-1', '要点'), bullets('blk-bullets-1', ['甲'])] })
    const plain = layoutScene(base)
    const withPin = layoutScene({
      ...base,
      blocks: [...base.blocks, image('blk-image-1')],
      // 完全落在内容区右边界(1220)之外
      layouts: [{ blockId: 'blk-image-1', frame: { x: 1221, y: 150, w: 59, h: 300 } }],
    })
    expect(withPin.frames.slice(0, 2)).toEqual(plain.frames.slice(0, 2))
  })

  it('钉住块不参与页级缩字,流式块仍按阶梯缩字', () => {
    const scene = makeScene({
      blocks: [bullets('blk-bullets-1', Array(7).fill(longText)), heading('blk-heading-1', '钉住的标题')],
      layouts: [{ blockId: 'blk-heading-1', frame: { x: 900, y: 40, w: 300 } }],
    })
    const result = layoutScene(scene)
    expect(result.overflow).toBe('shrunk')
    expect(result.frames.find((f) => f.blockId === 'blk-bullets-1')!.fontScale).toBeLessThan(1)
    expect(result.frames.find((f) => f.blockId === 'blk-heading-1')!.fontScale).toBe(1)
  })

  it('media-right 页的媒体块被钉住后,左区文字占全宽', () => {
    const scene = makeScene({
      preset: 'media-right',
      blocks: [bullets('blk-bullets-1', ['甲']), image('blk-image-1')],
      layouts: [{ blockId: 'blk-image-1', frame: { x: 900, y: 500, w: 300, h: 180 } }],
    })
    const text = layoutScene(scene).frames.find((f) => f.blockId === 'blk-bullets-1')!
    expect(text.w).toBe(1160)
  })

  it('文字块钉在页底、内容出页 → overflow error 且诊断点名', () => {
    const scene = makeScene({
      blocks: [heading('blk-heading-1', '要点'), bullets('blk-bullets-1', [longText, longText, longText])],
      layouts: [{ blockId: 'blk-bullets-1', frame: { x: 60, y: 650, w: 600 } }],
    })
    expect(layoutScene(scene).overflow).toBe('error')
    expect(describeOverflow(scene)).toMatch(/钉住的块 blk-bullets-1 底边超出页面 \d+px/)
  })
})

describe('layoutScene — 字号档', () => {
  it('large 档放大流式块的字号倍率与高度;normal 块不受影响', () => {
    const scene = makeScene({ blocks: [heading('blk-heading-1', '要点'), bullets('blk-bullets-1', [longText])] })
    const plain = layoutScene(scene)
    const sized = layoutScene({ ...scene, layouts: [{ blockId: 'blk-bullets-1', size: 'large' }] })
    const plainList = plain.frames.find((f) => f.blockId === 'blk-bullets-1')!
    const sizedList = sized.frames.find((f) => f.blockId === 'blk-bullets-1')!
    expect(sizedList.fontScale).toBeCloseTo(1.2, 5)
    expect(sizedList.h).toBeGreaterThan(plainList.h)
    expect(sized.frames.find((f) => f.blockId === 'blk-heading-1')!.fontScale).toBe(1)
  })

  it('字号档叠在页级缩字之上;columns 的字号档作用于全部子块', () => {
    const scene = makeScene({
      preset: 'two-column',
      blocks: [
        {
          id: 'blk-columns-1',
          type: 'columns',
          children: [[heading('blk-heading-1', '左') as never], [heading('blk-heading-2', '右') as never]],
        },
      ],
      layouts: [{ blockId: 'blk-columns-1', size: 'small' }],
    })
    const result = layoutScene(scene)
    for (const id of ['blk-columns-1', 'blk-heading-1', 'blk-heading-2']) {
      expect(result.frames.find((f) => f.blockId === id)!.fontScale).toBeCloseTo(0.85, 5)
    }
  })

  it('钉住 + 字号档同时生效:钉住块的倍率就是字号档', () => {
    const scene = makeScene({
      blocks: [heading('blk-heading-1', '要点'), heading('blk-heading-2', '钉住')],
      layouts: [{ blockId: 'blk-heading-2', frame: { x: 700, y: 300, w: 500 }, size: 'xlarge' }],
    })
    const pinned = layoutScene(scene).frames.find((f) => f.blockId === 'blk-heading-2')!
    expect(pinned.pinned).toBe(true)
    expect(pinned.fontScale).toBeCloseTo(1.45, 5)
  })

  it('孤儿排版覆盖(指向不存在的块)被引擎忽略', () => {
    const scene = makeScene({
      blocks: [heading('blk-heading-1', '要点')],
      layouts: [{ blockId: 'blk-nope', frame: { x: 0, y: 0, w: 300 }, size: 'large' }],
    })
    const result = layoutScene(scene)
    expect(result.frames).toHaveLength(1)
    expect(result.frames[0]!.pinned).toBe(false)
  })
})
