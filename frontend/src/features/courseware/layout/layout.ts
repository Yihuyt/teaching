/**
 * 主布局算法:layoutScene(scene, theme) → PositionedScene。
 *
 * 流程:预设定区 → 钉住块按排版覆盖直接成帧(不参与后续任何一步)→ 其余块分配到区域 →
 * 区域内纵向流式排布(columns 就地分栏,弹性块吃剩余高度,遇到钉住块占的纵向区间上下环绕跳过)
 * → 溢出则按 [1, 0.92, 0.85, 0.78] 逐级缩字重排 → 仍溢出返回 overflow:"error"(帧按最小档给出)。
 * 块字号档(排版覆盖里的 size)是叠在页级缩字之上的整块倍率。
 *
 * 引擎不自动分页:分页是内容决策,交给生成管线回喂或用户拆页。
 */
import type { Block, BlockLayout, LeafBlock, Scene } from '@/features/courseware/dsl'
import { BLOCK_SIZE_SCALES, FREE_HEIGHT_TYPES, isBlocklessScene } from '@/features/courseware/dsl/stage.ts'
import type { ThemeTokens } from '@/features/courseware/layout/theme.ts'
import { DEFAULT_THEME } from '@/features/courseware/layout/theme.ts'
import type { Frame, PositionedScene, TitleFrame } from '@/features/courseware/layout/types.ts'
import { getPreset, type Region } from '@/features/courseware/layout/presets.ts'
import { measureLeafBlock } from '@/features/courseware/layout/measureBlock.ts'
import { wrapText } from '@/features/courseware/layout/measure.ts'

const FONT_SCALES = [1, 0.92, 0.85, 0.78] as const

const MEDIA_TYPES = new Set<Block['type']>(['chart', 'image', 'code', 'table'])

interface Band {
  top: number
  bottom: number
}

interface FlowResult {
  frames: Frame[]
  usedH: number
  fits: boolean
}

/** 排版覆盖索引:只认引用顶层块的条目(校验保证如此;引擎对孤儿条目视而不见) */
function indexLayouts(scene: Scene): Map<string, BlockLayout> {
  const map = new Map<string, BlockLayout>()
  const topLevel = new Set(scene.blocks.map((b) => b.id))
  for (const layout of scene.layouts ?? []) {
    if (topLevel.has(layout.blockId)) map.set(layout.blockId, layout)
  }
  return map
}

function sizeScale(layout: BlockLayout | undefined): number {
  return layout?.size ? BLOCK_SIZE_SCALES[layout.size] : 1
}

function isPinned(layout: BlockLayout | undefined): boolean {
  return layout?.frame !== undefined
}

function pinnedFrame(block: Block, layout: BlockLayout, theme: ThemeTokens): Frame {
  const frame = layout.frame!
  const scale = sizeScale(layout)
  let h: number
  if (FREE_HEIGHT_TYPES.has(block.type) && frame.h !== undefined) {
    h = frame.h
  } else {
    // columns 不可钉住(校验保证),这里只会是叶子块或 quiz_choice
    const m = measureLeafBlock(block as LeafBlock, frame.w, theme, scale)
    h = m.kind === 'fixed' ? m.h : m.minH
  }
  return { blockId: block.id, x: frame.x, y: frame.y, w: frame.w, h, fontScale: scale, pinned: true }
}

function bandsFor(region: Region, pinned: Frame[], gap: number): Band[] {
  const bands: Band[] = []
  for (const f of pinned) {
    if (f.x < region.x + region.w && f.x + f.w > region.x) {
      bands.push({ top: f.y - gap, bottom: f.y + f.h + gap })
    }
  }
  bands.sort((a, b) => a.top - b.top)
  return bands
}

function skipBands(y: number, h: number, bands: Band[]): number {
  let moved = true
  while (moved) {
    moved = false
    for (const band of bands) {
      if (y < band.bottom && y + h > band.top) {
        y = band.bottom
        moved = true
      }
    }
  }
  return y
}

/** 区域内纵向流式排布(第一遍定高,第二遍把剩余高度分给弹性块;弹性块撑开后放不下则退回最小高) */
function flowRegion(
  blocks: Block[],
  region: Region,
  theme: ThemeTokens,
  scale: number,
  layouts: Map<string, BlockLayout>,
  pinned: Frame[],
): FlowResult {
  const gap = theme.spacing.md * scale
  const bands = bandsFor(region, pinned, gap)

  interface Entry {
    block: Block
    h: number
    flex: boolean
    fontScale: number
    /** columns 容器的子帧(相对量在 finalize 时计算) */
    children?: Frame[]
  }

  const entries: Entry[] = []

  for (const block of blocks) {
    const blockScale = scale * sizeScale(layouts.get(block.id))
    if (block.type === 'columns') {
      const colGap = theme.spacing.lg * blockScale
      const ratios =
        block.ratio && block.ratio.length === block.children.length
          ? block.ratio
          : block.children.map(() => 1)
      const ratioSum = ratios.reduce((a, b) => a + b, 0)
      const availW = region.w - colGap * (block.children.length - 1)

      const columnFrames: Frame[] = []
      let maxColH = 0
      let colX = region.x
      block.children.forEach((col, ci) => {
        const colW = (availW * (ratios[ci] ?? 1)) / ratioSum
        let y = 0
        col.forEach((child: LeafBlock, bi) => {
          if (bi > 0) y += theme.spacing.sm * blockScale
          const m = measureLeafBlock(child, colW, theme, blockScale)
          const h = m.kind === 'fixed' ? m.h : m.minH
          columnFrames.push({ blockId: child.id, x: colX, y, w: colW, h, fontScale: blockScale, pinned: false })
          y += h
        })
        maxColH = Math.max(maxColH, y)
        colX += colW + colGap
      })

      entries.push({ block, h: maxColH, flex: false, fontScale: blockScale, children: columnFrames })
      continue
    }

    const m = measureLeafBlock(block, region.w, theme, blockScale)
    entries.push({
      block,
      h: m.kind === 'fixed' ? m.h : m.minH,
      flex: m.kind === 'flex',
      fontScale: blockScale,
    })
  }

  const place = (bonus: number): FlowResult => {
    const frames: Frame[] = []
    let y = region.y
    for (const [i, e] of entries.entries()) {
      if (i > 0) y += gap
      const h = e.flex ? e.h + bonus : e.h
      y = skipBands(y, h, bands)
      frames.push({ blockId: e.block.id, x: region.x, y, w: region.w, h, fontScale: e.fontScale, pinned: false })
      if (e.children) {
        for (const child of e.children) {
          frames.push({ ...child, y: y + child.y })
        }
      }
      y += h
    }
    return { frames, usedH: y - region.y, fits: y - region.y <= region.h + 0.5 }
  }

  const base = place(0)
  // 弹性块均分剩余高度(只在放得下时扩展;撑开后被钉住块顶出区域则不扩展)
  const flexCount = entries.filter((e) => e.flex).length
  const leftover = region.h - base.usedH
  if (flexCount > 0 && leftover > 0) {
    const expanded = place(leftover / flexCount)
    if (expanded.fits) return expanded
  }
  return base
}

function assignBlocks(blocks: Block[], regionCount: number): Block[][] {
  if (regionCount === 1) return [blocks]

  // media-right:最后一个媒体类块进右区,其余进左区
  let mediaIndex = -1
  for (let i = blocks.length - 1; i >= 0; i -= 1) {
    const b = blocks[i]
    if (b && MEDIA_TYPES.has(b.type)) {
      mediaIndex = i
      break
    }
  }
  if (mediaIndex === -1) return [blocks, []]
  return [blocks.filter((_, i) => i !== mediaIndex), [blocks[mediaIndex] as Block]]
}

function layoutTitle(scene: Scene, theme: ThemeTokens): TitleFrame | null {
  const preset = getPreset(scene.preset, theme)
  if (!preset.titleBar) return null
  const bar = preset.titleBar

  let fontSize = bar.fontSize
  let lines = wrapText(scene.title, fontSize, bar.w)
  if (lines.length > 2) {
    fontSize = Math.round(fontSize * 0.85)
    lines = wrapText(scene.title, fontSize, bar.w)
  }
  const h = Math.max(1, lines.length) * fontSize * theme.lineHeight
  return { x: bar.x, y: bar.y, w: bar.w, h, fontSize, align: bar.align, lines }
}

/**
 * 实际生效的预设:media-right 页若流式块里没有任何媒体块,右栏无物可放,
 * 强行分栏只会把全部内容挤进窄左栏 —— 确定性降级为 standard 全宽排版。
 */
function effectivePresetName(scene: Scene, flowBlocks: Block[]): Scene['preset'] {
  if (scene.preset === 'media-right' && !flowBlocks.some((b) => MEDIA_TYPES.has(b.type))) {
    return 'standard'
  }
  return scene.preset
}

function splitPinned(scene: Scene, theme: ThemeTokens): { layouts: Map<string, BlockLayout>; pinned: Frame[]; flow: Block[] } {
  const layouts = indexLayouts(scene)
  const pinned: Frame[] = []
  const flow: Block[] = []
  for (const block of scene.blocks) {
    const layout = layouts.get(block.id)
    if (isPinned(layout)) pinned.push(pinnedFrame(block, layout!, theme))
    else flow.push(block)
  }
  return { layouts, pinned, flow }
}

/** 钉住块底边超出页面的像素数(文字类块的高度由内容量出,钉在下方时可能出页) */
function pinnedOverflowPx(frame: Frame, theme: ThemeTokens): number {
  return Math.ceil(frame.y + frame.h - theme.canvas.height)
}

export function layoutScene(scene: Scene, theme: ThemeTokens = DEFAULT_THEME): PositionedScene {
  const title = layoutTitle(scene, theme)

  // 交互页 / 视频页没有块,网页或视频占满内容区(用首个区域作帧)
  if (isBlocklessScene(scene.type)) {
    const region = getPreset(scene.preset, theme).regions[0]
    if (!region) throw new Error(`预设 ${scene.preset} 没有内容区`)
    return {
      sceneId: scene.id,
      preset: scene.preset,
      fontScale: 1,
      overflow: 'none',
      title,
      frames: [{ blockId: `__${scene.type}__`, ...region, fontScale: 1, pinned: true }],
    }
  }

  const { layouts, pinned, flow } = splitPinned(scene, theme)
  const preset = getPreset(effectivePresetName(scene, flow), theme)
  const assigned = assignBlocks(flow, preset.regions.length)
  const pinnedOut = pinned.some((f) => pinnedOverflowPx(f, theme) > 0)

  let lastFrames: Frame[] = []
  let lastScale: number = FONT_SCALES[0]

  for (const scale of FONT_SCALES) {
    const frames: Frame[] = []
    let allFit = true
    for (const [ri, region] of preset.regions.entries()) {
      const blocks = assigned[ri] ?? []
      if (blocks.length === 0) continue
      const result = flowRegion(blocks, region, theme, scale, layouts, pinned)
      frames.push(...result.frames)
      if (!result.fits) allFit = false
    }
    lastFrames = frames
    lastScale = scale
    if (allFit) {
      return {
        sceneId: scene.id,
        preset: scene.preset,
        fontScale: scale,
        overflow: pinnedOut ? 'error' : scale === 1 ? 'none' : 'shrunk',
        title,
        frames: [...frames, ...pinned],
      }
    }
  }

  return {
    sceneId: scene.id,
    preset: scene.preset,
    fontScale: lastScale,
    overflow: 'error',
    title,
    frames: [...lastFrames, ...pinned],
  }
}

/**
 * 溢出诊断:供生成管线把"具体超了多少"回喂给 LLM。
 * 返回 null 表示未溢出。
 */
export function describeOverflow(scene: Scene, theme: ThemeTokens = DEFAULT_THEME): string | null {
  const positioned = layoutScene(scene, theme)
  if (positioned.overflow !== 'error') return null

  const { layouts, pinned, flow } = splitPinned(scene, theme)
  const preset = getPreset(effectivePresetName(scene, flow), theme)
  const assigned = assignBlocks(flow, preset.regions.length)
  const details: string[] = []
  for (const [ri, region] of preset.regions.entries()) {
    const blocks = assigned[ri] ?? []
    if (blocks.length === 0) continue
    const result = flowRegion(blocks, region, theme, FONT_SCALES[FONT_SCALES.length - 1] as number, layouts, pinned)
    if (!result.fits) {
      const overPx = Math.ceil(result.usedH - region.h)
      const overRatio = Math.round((overPx / region.h) * 100)
      details.push(
        `内容区${preset.regions.length > 1 ? ` ${ri + 1}` : ''}在最小字号下仍超出 ${overPx}px(约 ${overRatio}%)`,
      )
    }
  }
  for (const frame of pinned) {
    const overPx = pinnedOverflowPx(frame, theme)
    if (overPx > 0) {
      details.push(`钉住的块 ${frame.blockId} 底边超出页面 ${overPx}px`)
    }
  }
  return `页面内容放不下:${details.join(';')}。`
}
