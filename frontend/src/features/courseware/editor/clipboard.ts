/**
 * 页内块 id 分配与复制粘贴:复制的是块内容 + 它的排版覆盖,粘贴到当前页末尾并分配新 id,
 * 钉住的块粘贴后错开一段距离再钉住,避免与原块完全重叠。
 */
import type { Block, BlockLayout, EditOp, Scene } from '@/features/courseware/dsl'

/** 页内下一个可用的 `blk-{type}-{n}`(顶层与 columns 子块的 id 都算占用) */
export function nextBlockId(scene: Pick<Scene, 'blocks'>, type: Block['type']): string {
  const taken = new Set<string>()
  for (const b of scene.blocks) {
    taken.add(b.id)
    if (b.type === 'columns') for (const col of b.children) for (const c of col) taken.add(c.id)
  }
  let n = 1
  while (taken.has(`blk-${type}-${n}`)) n += 1
  return `blk-${type}-${n}`
}

export interface BlockClip {
  block: Block
  layout: BlockLayout | undefined
}

const PASTE_OFFSET = 24

export function pasteOps(scene: Scene, clip: BlockClip, sceneSize: { width: number; height: number }): { ops: EditOp[]; blockId: string } {
  const id = nextBlockId(scene, clip.block.type)
  const block = withId(clip.block, id)
  const ops: EditOp[] = [{ op: 'add_block', sceneId: scene.id, index: scene.blocks.length, block }]
  const frame = clip.layout?.frame
  if (frame) {
    const h = frame.h
    const x = Math.min(frame.x + PASTE_OFFSET, sceneSize.width - frame.w)
    const y = Math.min(frame.y + PASTE_OFFSET, sceneSize.height - (h ?? 0))
    ops.push({ op: 'pin_block', sceneId: scene.id, blockId: id, x: Math.max(0, x), y: Math.max(0, y), w: frame.w, ...(h !== undefined ? { h } : {}) })
  }
  if (clip.layout?.size) {
    ops.push({ op: 'set_block_size', sceneId: scene.id, blockId: id, size: clip.layout.size })
  }
  return { ops, blockId: id }
}

/** 深拷贝并换 id;columns 的子块也各自换 id(同一页内不能撞车) */
function withId(block: Block, id: string): Block {
  const copy = JSON.parse(JSON.stringify(block)) as Block
  copy.id = id
  if (copy.type === 'columns') {
    let n = 0
    copy.children = copy.children.map((col) => col.map((child) => ({ ...child, id: `${id}-${(n += 1)}` })))
  }
  return copy
}
