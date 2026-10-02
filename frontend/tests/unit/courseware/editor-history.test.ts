import { describe, it, expect } from 'vitest'
import type { Stage, EditOp } from '@/features/courseware/dsl'
import { createHistory, invertOps, MAX_HISTORY } from '@/features/courseware/editor/history'

function stage(partial: Partial<Stage['scenes'][number]> = {}): Stage {
  return {
    title: '课',
    theme: 'default',
    scenes: [
      {
        id: 'scene-1',
        type: 'content',
        title: '一',
        preset: 'standard',
        summary: '概要',
        blocks: [
          { id: 'blk-paragraph-1', type: 'paragraph', text: '甲' },
          { id: 'blk-paragraph-2', type: 'paragraph', text: '乙' },
        ],
        speech: [{ text: '看这里。', actions: [{ type: 'highlight', target: 'blk-paragraph-2' }] }],
        ...partial,
      },
    ],
  }
}

describe('invertOps', () => {
  it('块操作:增 → 删,删 → 按原位加回并恢复讲稿与排版覆盖', () => {
    const before = stage({ layouts: [{ blockId: 'blk-paragraph-2', frame: { x: 10, y: 20, w: 300 }, size: 'large' }] })
    const del: EditOp = { op: 'delete_block', sceneId: 'scene-1', blockId: 'blk-paragraph-2' }
    const after = stage({ blocks: [before.scenes[0]!.blocks[0]!], speech: [{ text: '看这里。', actions: [] }], layouts: [] })
    const inverse = invertOps([del], before, after)!
    expect(inverse[0]).toEqual({ op: 'add_block', sceneId: 'scene-1', index: 1, block: before.scenes[0]!.blocks[1] })
    expect(inverse[1]).toEqual({ op: 'pin_block', sceneId: 'scene-1', blockId: 'blk-paragraph-2', x: 10, y: 20, w: 300 })
    expect(inverse[2]).toEqual({ op: 'set_block_size', sceneId: 'scene-1', blockId: 'blk-paragraph-2', size: 'large' })
    expect(inverse[3]).toEqual({ op: 'set_speech', sceneId: 'scene-1', speech: [{ text: '看这里。', actions: [{ type: 'highlight', target: 'blk-paragraph-2' }] }] })

    const add: EditOp = { op: 'add_block', sceneId: 'scene-1', index: 0, block: { id: 'blk-heading-1', type: 'heading', level: 2, text: '新' } }
    expect(invertOps([add], before, before)).toEqual([{ op: 'delete_block', sceneId: 'scene-1', blockId: 'blk-heading-1' }])
  })

  it('排版覆盖:钉住 → 解除;解除 → 钉回原处;改字号档 → 改回', () => {
    const before = stage()
    const pinned = stage({ layouts: [{ blockId: 'blk-paragraph-1', frame: { x: 0, y: 0, w: 300 } }] })
    const pin: EditOp = { op: 'pin_block', sceneId: 'scene-1', blockId: 'blk-paragraph-1', x: 0, y: 0, w: 300 }
    expect(invertOps([pin], before, pinned)).toEqual([{ op: 'unpin_block', sceneId: 'scene-1', blockId: 'blk-paragraph-1' }])
    const unpin: EditOp = { op: 'unpin_block', sceneId: 'scene-1', blockId: 'blk-paragraph-1' }
    expect(invertOps([unpin], pinned, before)).toEqual([pin])
    const size: EditOp = { op: 'set_block_size', sceneId: 'scene-1', blockId: 'blk-paragraph-1', size: 'large' }
    const sized = stage({ layouts: [{ blockId: 'blk-paragraph-1', size: 'large' }] })
    expect(invertOps([size], before, sized)).toEqual([{ op: 'set_block_size', sceneId: 'scene-1', blockId: 'blk-paragraph-1', size: 'normal' }])
  })

  it('批内反序;删页不可逆整批为 null', () => {
    const before = stage()
    const ops: EditOp[] = [
      { op: 'update_scene_meta', sceneId: 'scene-1', title: '改' },
      { op: 'move_block', sceneId: 'scene-1', blockId: 'blk-paragraph-2', toIndex: 0 },
    ]
    const inverse = invertOps(ops, before, before)!
    expect(inverse.map((o) => o.op)).toEqual(['move_block', 'update_scene_meta'])
    expect(inverse[0]).toMatchObject({ toIndex: 1 })
    expect(inverse[1]).toMatchObject({ title: '一' })
    expect(invertOps([{ op: 'delete_scene', sceneId: 'scene-1' }], before, before)).toBeNull()
  })

  it('视频页:换视频 → 换回原视频', () => {
    const before = stage()
    before.scenes.push({ id: 'scene-2', type: 'video', title: '视频', preset: 'standard', summary: '看', blocks: [], speech: [], video: { src: 'courseware/1/videos/a.mp4' } })
    const replace: EditOp = { op: 'set_video', sceneId: 'scene-2', src: 'courseware/1/videos/b.mp4' }
    expect(invertOps([replace], before, before)).toEqual([{ op: 'set_video', sceneId: 'scene-2', src: 'courseware/1/videos/a.mp4' }])
  })

  it('交互页:新增 → 删页;换网页 → 换回原网页', () => {
    const before = stage()
    const added = stage()
    added.scenes.push({
      id: 'scene-2',
      type: 'interactive',
      title: '仿真',
      preset: 'standard',
      summary: '动手',
      blocks: [],
      speech: [],
      interactive: { html: '<html><body>新</body></html>' },
    })
    const add: EditOp = { op: 'add_interactive_scene', index: 1, title: '仿真', summary: '动手', html: '<html></html>' }
    expect(invertOps([add], before, added)).toEqual([{ op: 'delete_scene', sceneId: 'scene-2' }])

    const replace: EditOp = { op: 'set_interactive_html', sceneId: 'scene-2', html: '<html><body>更新</body></html>' }
    expect(invertOps([replace], added, added)).toEqual([
      { op: 'set_interactive_html', sceneId: 'scene-2', html: '<html><body>新</body></html>' },
    ])
  })
})

describe('createHistory', () => {
  it('推入 / 撤销 / 重做 / 上限', () => {
    const history = createHistory()
    expect(history.canUndo).toBe(false)
    const entry = { undo: [], redo: [] }
    history.push(entry)
    expect(history.canUndo).toBe(true)
    expect(history.undo()).toBe(entry)
    expect(history.canRedo).toBe(true)
    expect(history.redo()).toBe(entry)
    history.push({ undo: [], redo: [] })
    expect(history.canRedo).toBe(false)
    for (let i = 0; i < MAX_HISTORY + 5; i += 1) history.push({ undo: [], redo: [] })
    let count = 0
    while (history.undo()) count += 1
    expect(count).toBe(MAX_HISTORY)
  })
})
