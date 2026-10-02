/**
 * 单块高度测量:块 + 可用宽度 + 字号缩放 → 高度需求。
 *
 * chart 是"弹性块":先占最小高度,布局时把区域剩余高度分给它;
 * 其余块高度固定由内容决定。
 */
import type { LeafBlock, QuizChoiceBlock } from '@/features/courseware/dsl'
import type { ThemeTokens } from '@/features/courseware/layout/theme.ts'
import { textHeight, countLines } from '@/features/courseware/layout/measure.ts'

export type MeasuredHeight = { kind: 'fixed'; h: number } | { kind: 'flex'; minH: number }

/** bullets 一级条目符号/序号的缩进宽度(em,相对正文字号) */
const BULLET_INDENT_EM = 1.4
/** bullets 二级条目缩进(em) */
const SUB_INDENT_EM = 2.8
const CODE_PADDING = 14
const CALLOUT_PADDING = 16
/** 图片块:最大高度与尺寸未知时的默认高度(与 Java BlockMeasurer 同值) */
const IMAGE_MAX_HEIGHT = 300
const IMAGE_DEFAULT_HEIGHT = 180
/** 表格单元格上下内边距合计 */
const CELL_PADDING_Y = 14
/** 表格单元格左右内边距合计 */
const CELL_PADDING_X = 20

export function measureLeafBlock(
  block: LeafBlock | QuizChoiceBlock,
  width: number,
  theme: ThemeTokens,
  scale: number,
): MeasuredHeight {
  const fs = {
    h1: theme.fontSize.h1 * scale,
    h2: theme.fontSize.h2 * scale,
    body: theme.fontSize.body * scale,
    small: theme.fontSize.small * scale,
    code: theme.fontSize.code * scale,
  }
  const lh = theme.lineHeight

  switch (block.type) {
    case 'heading': {
      const size = block.level === 1 ? fs.h1 : fs.h2
      return { kind: 'fixed', h: textHeight(block.text, size, width, lh) }
    }

    case 'paragraph':
      return { kind: 'fixed', h: textHeight(block.text, fs.body, width, lh) }

    case 'bullets': {
      let h = 0
      const itemGap = theme.spacing.xs * scale
      const indent = fs.body * BULLET_INDENT_EM
      const subIndent = fs.body * SUB_INDENT_EM
      block.items.forEach((item, i) => {
        if (i > 0) h += itemGap
        h += textHeight(item.text, fs.body, width - indent, lh)
        for (const sub of item.sub ?? []) {
          h += textHeight(sub, fs.small, width - subIndent, lh)
        }
      })
      return { kind: 'fixed', h }
    }

    case 'formula': {
      // 展示级公式:按 \\ 换行数估行,单行高约 2.2 倍正文字号
      const rows = block.latex.split('\\\\').length
      let h = Math.max(70 * scale, rows * fs.body * 2.2)
      if (block.caption) {
        h += theme.spacing.xs * scale + textHeight(block.caption, fs.small, width, lh)
      }
      return { kind: 'fixed', h }
    }

    case 'code': {
      const charEm = 0.6 // 等宽字宽
      const maxCols = Math.max(10, Math.floor(width / (fs.code * charEm)))
      let lines = 0
      for (const raw of block.code.split('\n')) {
        lines += Math.max(1, Math.ceil(raw.length / maxCols))
      }
      let h = lines * fs.code * theme.codeLineHeight + CODE_PADDING * 2 * scale
      if (block.caption) {
        h += theme.spacing.xs * scale + textHeight(block.caption, fs.small, width, lh)
      }
      return { kind: 'fixed', h }
    }

    case 'table': {
      const colW = (width - CELL_PADDING_X * block.headers.length) / block.headers.length
      const rowHeight = (cells: string[], size: number): number => {
        let maxLines = 1
        for (const cell of cells) {
          maxLines = Math.max(maxLines, countLines(cell, size, colW))
        }
        return maxLines * size * lh + CELL_PADDING_Y * scale
      }
      let h = rowHeight(block.headers, fs.small)
      for (const row of block.rows) h += rowHeight(row, fs.small)
      if (block.caption) {
        h += theme.spacing.xs * scale + textHeight(block.caption, fs.small, width, lh)
      }
      return { kind: 'fixed', h }
    }

    case 'chart': {
      let minH = 220 * scale
      if (block.caption) {
        minH += theme.spacing.xs * scale + textHeight(block.caption, fs.small, width, lh)
      }
      return { kind: 'flex', minH }
    }

    case 'emphasis': {
      let h = textHeight(block.text, fs.h1, width, lh)
      if (block.caption) {
        h += theme.spacing.xs * scale + textHeight(block.caption, fs.small, width, lh)
      }
      return { kind: 'fixed', h }
    }

    case 'image': {
      const ratio = block.width > 0 && block.height > 0 ? block.width / block.height : 0
      let h = ratio > 0 ? Math.min(width / ratio, IMAGE_MAX_HEIGHT * scale) : IMAGE_DEFAULT_HEIGHT * scale
      if (block.caption) {
        h += theme.spacing.xs * scale + textHeight(block.caption, fs.small, width, lh)
      }
      return { kind: 'fixed', h }
    }

    case 'callout': {
      // 视图无 title 时也会渲染变体标签行("提示"等),测量必须始终含标题行
      const innerW = width - CALLOUT_PADDING * 2 * scale
      let h = CALLOUT_PADDING * 2 * scale
      h += textHeight(block.title ?? '提示', fs.body, innerW, lh) + theme.spacing.xs * scale
      h += textHeight(block.text, fs.small, innerW, lh)
      return { kind: 'fixed', h }
    }

    case 'quiz_choice': {
      // 与 QuizChoiceView 对应:选项框内边距 8px×2 + 边框 1.5px×2,
      // 作答模式还有一行提交按钮,始终预留
      const OPTION_BOX_EXTRA = 19
      const BUTTON_RESERVE = 56
      const optionIndent = fs.body * 2.2 + 12 * 2 * scale // "A." 标号 + 选项框左右内边距
      let h = textHeight(block.stem, fs.body, width, lh)
      h += theme.spacing.sm * scale
      block.options.forEach((option, i) => {
        if (i > 0) h += theme.spacing.xs * scale
        h += textHeight(option.text, fs.body, width - optionIndent, lh) + OPTION_BOX_EXTRA * scale
      })
      h += theme.spacing.sm * scale + BUTTON_RESERVE * scale
      // explanation 不参与页面排版(作答后由播放器覆盖层展示)
      return { kind: 'fixed', h }
    }
  }
}
