import { describe, it, expect } from 'vitest'
import type { Scene } from '@/features/courseware/dsl'
import { layoutScene } from '@/features/courseware/layout'

describe('media-right 无媒体块时降级为全宽排版', () => {
  const base: Omit<Scene, 'blocks'> = {
    id: 'p1',
    type: 'content',
    title: '标题',
    preset: 'media-right',
    speech: [],
  }

  it('无媒体块:文字块占全宽(1160),不再挤进 620 左栏', () => {
    const scene: Scene = {
      ...base,
      blocks: [
        { id: 'blk-paragraph-1', type: 'paragraph', text: '一段说明文字。' },
        { id: 'blk-bullets-1', type: 'bullets', items: [{ text: '要点' }] },
      ],
    }
    const result = layoutScene(scene)
    const frame = result.frames.find((f) => f.blockId === 'blk-paragraph-1')
    expect(frame!.w).toBe(1160)
  })

  it('有媒体块:维持左右分区', () => {
    const scene: Scene = {
      ...base,
      blocks: [
        { id: 'blk-bullets-1', type: 'bullets', items: [{ text: '要点' }] },
        { id: 'blk-code-1', type: 'code', language: 'python', code: 'print(1)' },
      ],
    }
    const result = layoutScene(scene)
    const text = result.frames.find((f) => f.blockId === 'blk-bullets-1')
    const code = result.frames.find((f) => f.blockId === 'blk-code-1')
    expect(text!.w).toBe(620)
    expect(code!.x).toBeGreaterThan(620)
  })
})
