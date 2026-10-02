import { describe, it, expect } from 'vitest'
import type { Block, Scene } from '@/features/courseware/dsl'
import { layoutScene, describeOverflow, DEFAULT_THEME } from '@/features/courseware/layout'
import type { Frame } from '@/features/courseware/layout'

const heading = (id: string, text: string): Block => ({ id, type: 'heading', level: 2, text })
const bullets = (id: string, texts: string[]): Block => ({
  id,
  type: 'bullets',
  items: texts.map((text) => ({ text })),
})

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

/** 帧几何不变量:页内、不越界、同区域顶层帧互不重叠 */
function assertFrameInvariants(frames: Frame[], childIds: Set<string>): void {
  const { width, height } = DEFAULT_THEME.canvas
  for (const f of frames) {
    expect(f.x).toBeGreaterThanOrEqual(0)
    expect(f.y).toBeGreaterThanOrEqual(0)
    expect(f.x + f.w).toBeLessThanOrEqual(width + 0.5)
    expect(f.y + f.h).toBeLessThanOrEqual(height + 0.5)
  }
  const top = frames.filter((f) => !childIds.has(f.blockId))
  for (let i = 0; i < top.length; i += 1) {
    for (let j = i + 1; j < top.length; j += 1) {
      const a = top[i]!
      const b = top[j]!
      const overlapX = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)
      const overlapY = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y)
      expect(overlapX <= 0.5 || overlapY <= 0.5).toBe(true)
    }
  }
}

describe('layoutScene — 常规排布', () => {
  it('standard 页:标题 + 块自上而下,间距为 spacing.md', () => {
    const scene = makeScene({
      blocks: [
        heading('blk-heading-1', '本节要点'),
        bullets('blk-bullets-1', ['第一点', '第二点', '第三点']),
      ],
    })
    const result = layoutScene(scene)

    expect(result.overflow).toBe('none')
    expect(result.fontScale).toBe(1)
    expect(result.title?.lines).toEqual(['牛顿第二定律'])

    const [f1, f2] = result.frames
    expect(f1?.blockId).toBe('blk-heading-1')
    expect(f2?.blockId).toBe('blk-bullets-1')
    expect(f2!.y).toBeCloseTo(f1!.y + f1!.h + DEFAULT_THEME.spacing.md, 5)
    assertFrameInvariants(result.frames, new Set())
  })

  it('弹性块(chart)吃掉区域剩余高度', () => {
    const scene = makeScene({
      blocks: [
        heading('blk-heading-1', '销量对比'),
        {
          id: 'blk-chart-1',
          type: 'chart',
          chartType: 'bar',
          categories: ['一月', '二月'],
          series: [{ name: '销量', data: [10, 20] }],
        },
      ],
    })
    const result = layoutScene(scene)
    const chart = result.frames.find((f) => f.blockId === 'blk-chart-1')
    // 220 是最小高,弹性扩展后应显著更高
    expect(chart!.h).toBeGreaterThan(300)
    expect(result.overflow).toBe('none')
  })

  it('columns 块就地分栏,列宽按 ratio 分配', () => {
    const scene = makeScene({
      preset: 'two-column',
      blocks: [
        {
          id: 'blk-columns-1',
          type: 'columns',
          ratio: [7, 5],
          children: [
            [heading('blk-heading-1', '左列') as never, bullets('blk-bullets-1', ['甲', '乙']) as never],
            [heading('blk-heading-2', '右列') as never],
          ],
        },
      ],
    })
    const result = layoutScene(scene)
    const left = result.frames.find((f) => f.blockId === 'blk-heading-1')
    const right = result.frames.find((f) => f.blockId === 'blk-heading-2')
    expect(left!.x).toBeLessThan(right!.x)
    expect(left!.w / right!.w).toBeCloseTo(7 / 5, 1)
    const childIds = new Set(['blk-heading-1', 'blk-bullets-1', 'blk-heading-2'])
    assertFrameInvariants(result.frames, childIds)
  })

  it('media-right:最后一个媒体块进右区,其余进左区', () => {
    const scene = makeScene({
      preset: 'media-right',
      blocks: [
        bullets('blk-bullets-1', ['要点甲', '要点乙']),
        {
          id: 'blk-code-1',
          type: 'code',
          language: 'python',
          code: 'print("hello")',
        },
      ],
    })
    const result = layoutScene(scene)
    const text = result.frames.find((f) => f.blockId === 'blk-bullets-1')
    const code = result.frames.find((f) => f.blockId === 'blk-code-1')
    expect(text!.x).toBe(60)
    expect(code!.x).toBeGreaterThan(600)
    assertFrameInvariants(result.frames, new Set())
  })

  it('interactive 页产出占满内容区的 __interactive__ 帧', () => {
    const scene = makeScene({
      type: 'interactive',
      blocks: [],
      interactive: { html: '<!DOCTYPE html><html></html>' },
    })
    const result = layoutScene(scene)
    expect(result.frames).toHaveLength(1)
    expect(result.frames[0]?.blockId).toBe('__interactive__')
  })
})

describe('layoutScene — 溢出阶梯', () => {
  // 约 92 个汉字:原字号下每条占两行,是标定溢出阶梯的基准单位
  const longText =
    '这是一条相当长的要点内容,用来占据版面空间以测试布局引擎的溢出处理逻辑是否正确工作,' +
    '再补充一句让它在标准内容区宽度下无论如何都需要换行排成两行的额外说明文字以便标定阶梯。'

  it('内容适中 → none;偏多 → shrunk;过多 → error', () => {
    const few = makeScene({ blocks: [bullets('blk-bullets-1', Array(5).fill(longText))] })
    expect(layoutScene(few).overflow).toBe('none')

    const many = makeScene({ blocks: [bullets('blk-bullets-1', Array(7).fill(longText))] })
    const manyResult = layoutScene(many)
    expect(manyResult.overflow).toBe('shrunk')
    expect(manyResult.fontScale).toBeLessThan(1)

    const tooMany = makeScene({ blocks: [bullets('blk-bullets-1', Array(14).fill(longText))] })
    expect(layoutScene(tooMany).overflow).toBe('error')
  })

  it('describeOverflow 对溢出页给出可回喂的诊断,未溢出返回 null', () => {
    const ok = makeScene({ blocks: [heading('blk-heading-1', '标题')] })
    expect(describeOverflow(ok)).toBeNull()

    const bad = makeScene({ blocks: [bullets('blk-bullets-1', Array(20).fill(longText))] })
    const msg = describeOverflow(bad)
    expect(msg).toContain('超出')
    expect(msg).toMatch(/\d+px/)
  })
})

// 简单线性同余伪随机,种子固定保证可复现
function makeRng(seed: number): () => number {
  let s = seed
  return () => {
    s = (s * 1103515245 + 12345) % 2147483648
    return s / 2147483648
  }
}

describe('layoutScene — 属性测试(伪随机页面帧不变量)', () => {
  const texts = [
    '短文本',
    '中等长度的一段说明文字,包含逗号。',
    '相当长的一段解释性文字,里面有 English words 也有数字 12345,用来考验断行与测量的稳定性。',
  ]

  function randomScene(rng: () => number, index: number): Scene {
    const presets = ['standard', 'two-column', 'media-right'] as const
    const blockCount = 1 + Math.floor(rng() * 4)
    const blocks: Block[] = []
    for (let i = 0; i < blockCount; i += 1) {
      const pick = rng()
      const text = texts[Math.floor(rng() * texts.length)]!
      if (pick < 0.3) {
        blocks.push(heading(`blk-heading-${i + 1}`, text))
      } else if (pick < 0.6) {
        blocks.push(bullets(`blk-bullets-${i + 1}`, Array(1 + Math.floor(rng() * 4)).fill(text)))
      } else if (pick < 0.8) {
        blocks.push({ id: `blk-paragraph-${i + 1}`, type: 'paragraph', text })
      } else {
        blocks.push({
          id: `blk-callout-${i + 1}`,
          type: 'callout',
          variant: 'info',
          text,
        })
      }
    }
    return makeScene({
      id: `scene-${index}`,
      preset: presets[Math.floor(rng() * presets.length)]!,
      blocks,
    })
  }

  it('50 个伪随机页面:帧全部页内、顶层帧互不重叠', () => {
    const rng = makeRng(42)
    for (let i = 0; i < 50; i += 1) {
      const scene = randomScene(rng, i)
      const result = layoutScene(scene)
      if (result.overflow === 'error') continue // error 页允许纵向越界
      assertFrameInvariants(result.frames, new Set())
    }
  })
})
