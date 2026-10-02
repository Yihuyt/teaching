/**
 * 撤销 / 重做 —— 课件没有"未保存"态,每个改动都是已落库的编辑操作,所以撤销也是一条(批)反向操作再落库。
 * 反向操作由"操作 + 改动前的课件"推出;推不出来的(删页:讲稿音频与交互 HTML 无法用 add_scene 复原)
 * 整批不入栈,由调用方清栈。生成流水线写入后课件已不是教师上次看到的样子,调用方也清栈。
 */
import type { Block, BlockLayout, Stage, EditOp, Scene } from '@/features/courseware/dsl'
import { toEditSpeech } from '@/features/courseware/dsl'

interface HistoryEntry {
  /** 撤销要发的操作(已按反序排好) */
  undo: EditOp[]
  redo: EditOp[]
}

export const MAX_HISTORY = 50

interface History {
  readonly canUndo: boolean
  readonly canRedo: boolean
  push(entry: HistoryEntry): void
  undo(): HistoryEntry | undefined
  redo(): HistoryEntry | undefined
  clear(): void
}

export function createHistory(): History {
  const past: HistoryEntry[] = []
  const future: HistoryEntry[] = []
  return {
    get canUndo() {
      return past.length > 0
    },
    get canRedo() {
      return future.length > 0
    },
    push(entry) {
      past.push(entry)
      if (past.length > MAX_HISTORY) past.shift()
      future.length = 0
    },
    undo() {
      const entry = past.pop()
      if (entry) future.push(entry)
      return entry
    },
    redo() {
      const entry = future.pop()
      if (entry) past.push(entry)
      return entry
    },
    clear() {
      past.length = 0
      future.length = 0
    },
  }
}

function sceneOf(stage: Stage, sceneId: string): Scene | undefined {
  return stage.scenes.find((p) => p.id === sceneId)
}

function layoutOf(scene: Scene, blockId: string): BlockLayout | undefined {
  return scene.layouts?.find((l) => l.blockId === blockId)
}

function restoreLayoutOps(sceneId: string, blockId: string, before: BlockLayout | undefined, after: BlockLayout | undefined): EditOp[] {
  const ops: EditOp[] = []
  const beforeFrame = before?.frame
  const afterFrame = after?.frame
  if (beforeFrame) {
    ops.push({ op: 'pin_block', sceneId, blockId, x: beforeFrame.x, y: beforeFrame.y, w: beforeFrame.w, ...(beforeFrame.h !== undefined ? { h: beforeFrame.h } : {}) })
  } else if (afterFrame) {
    ops.push({ op: 'unpin_block', sceneId, blockId })
  }
  if ((before?.size ?? 'normal') !== (after?.size ?? 'normal')) {
    ops.push({ op: 'set_block_size', sceneId, blockId, size: before?.size ?? 'normal' })
  }
  return ops
}

/** 单条操作的反向操作;返回 null 表示不可逆 */
function invertOne(op: EditOp, before: Stage, after: Stage): EditOp[] | null {
  switch (op.op) {
    case 'update_stage_meta':
      return [{ op: 'update_stage_meta', title: before.title }]
    case 'add_scene':
    case 'add_interactive_scene':
    case 'add_video_scene': {
      const beforeIds = new Set(before.scenes.map((p) => p.id))
      const added = after.scenes.find((p) => !beforeIds.has(p.id))
      return added ? [{ op: 'delete_scene', sceneId: added.id }] : null
    }
    case 'delete_scene':
      return null
    case 'move_scene': {
      const index = before.scenes.findIndex((p) => p.id === op.sceneId)
      return index < 0 ? null : [{ op: 'move_scene', sceneId: op.sceneId, toIndex: index }]
    }
    case 'update_scene_meta': {
      const scene = sceneOf(before, op.sceneId)
      if (!scene) return null
      return [
        {
          op: 'update_scene_meta',
          sceneId: op.sceneId,
          ...(op.title !== undefined ? { title: scene.title } : {}),
          ...(op.preset !== undefined ? { preset: scene.preset } : {}),
          ...(op.summary !== undefined ? { summary: scene.summary ?? '' } : {}),
        },
      ]
    }
    case 'add_block':
      return [{ op: 'delete_block', sceneId: op.sceneId, blockId: op.block.id }]
    case 'replace_block': {
      const scene = sceneOf(before, op.sceneId)
      const block = scene?.blocks.find((b) => b.id === op.blockId)
      if (!scene || !block) return null
      return [{ op: 'replace_block', sceneId: op.sceneId, blockId: op.block.id, block }]
    }
    case 'delete_block': {
      const scene = sceneOf(before, op.sceneId)
      const index = scene?.blocks.findIndex((b) => b.id === op.blockId) ?? -1
      if (!scene || index < 0) return null
      const block = scene.blocks[index] as Block
      const ops: EditOp[] = [{ op: 'add_block', sceneId: op.sceneId, index, block }]
      const layout = layoutOf(scene, op.blockId)
      if (layout) ops.push(...restoreLayoutOps(op.sceneId, op.blockId, layout, undefined))
      // 删块时指向它的讲稿动作被清洗掉了:讲稿原样放回(文本没变的段保留音频)
      const mentioned = scene.speech.some((s) => s.actions.some((a) => a.type !== 'pause' && a.target.split('#')[0] === op.blockId))
      if (mentioned) ops.push({ op: 'set_speech', sceneId: op.sceneId, speech: toEditSpeech(scene.speech) })
      return ops
    }
    case 'move_block': {
      const scene = sceneOf(before, op.sceneId)
      const index = scene?.blocks.findIndex((b) => b.id === op.blockId) ?? -1
      return index < 0 ? null : [{ op: 'move_block', sceneId: op.sceneId, blockId: op.blockId, toIndex: index }]
    }
    case 'set_speech': {
      const scene = sceneOf(before, op.sceneId)
      return scene ? [{ op: 'set_speech', sceneId: op.sceneId, speech: toEditSpeech(scene.speech) }] : null
    }
    case 'set_video': {
      const src = sceneOf(before, op.sceneId)?.video?.src
      return src ? [{ op: 'set_video', sceneId: op.sceneId, src }] : null
    }
    case 'set_interactive_html': {
      const html = sceneOf(before, op.sceneId)?.interactive?.html
      return html ? [{ op: 'set_interactive_html', sceneId: op.sceneId, html }] : null
    }
    case 'pin_block':
    case 'unpin_block':
    case 'set_block_size': {
      const beforeScene = sceneOf(before, op.sceneId)
      const afterScene = sceneOf(after, op.sceneId)
      if (!beforeScene || !afterScene) return null
      return restoreLayoutOps(op.sceneId, op.blockId, layoutOf(beforeScene, op.blockId), layoutOf(afterScene, op.blockId))
    }
  }
}

/**
 * 一批操作的反向批:逐条求逆后反序拼接;任一条不可逆整批返回 null。
 * before / after 是这批操作落库前后的课件。
 */
export function invertOps(ops: EditOp[], before: Stage, after: Stage): EditOp[] | null {
  const inverted: EditOp[] = []
  for (let i = ops.length - 1; i >= 0; i -= 1) {
    const one = invertOne(ops[i]!, before, after)
    if (one === null) return null
    inverted.push(...one)
  }
  return inverted
}
