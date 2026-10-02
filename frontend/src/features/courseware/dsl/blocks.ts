/**
 * 块(Block)联合 —— LLM 生成课件时的唯一内容词汇表。
 *
 * 设计约束:
 * - 每个块必须有稳定 id(约定 `blk-{type}-{n}`),供讲课动作(highlight/reveal 等)定位;
 * - 块只描述语义与内容,不携带任何坐标/字号/颜色 —— 排版是 @/features/courseware/layout 的职责;
 * - `columns` 是唯一的容器块,且禁止嵌套 columns(布局引擎按一层分栏实现)。
 */

export interface BlockBase {
  /** 稳定块 id,约定 `blk-{type}-{n}`,页内唯一 */
  id: string
}

export interface HeadingBlock extends BlockBase {
  type: 'heading'
  /** 1 = 章节级大标题,2 = 小节标题 */
  level: 1 | 2
  text: string
}

/**
 * 正文段落。text 支持极小内联语法:`**加粗**` 与 `$latex$` 行内公式,
 * 除此之外一律按纯文本处理(渲染端不解析 HTML)。
 */
export interface ParagraphBlock extends BlockBase {
  type: 'paragraph'
  text: string
}

export interface BulletItem {
  text: string
  /** 二级子条目(纯文本,不再嵌套) */
  sub?: string[]
}

export interface BulletsBlock extends BlockBase {
  type: 'bullets'
  ordered?: boolean
  items: BulletItem[]
}

export interface FormulaBlock extends BlockBase {
  type: 'formula'
  /** 展示级 LaTeX(不含定界符 $$) */
  latex: string
  caption?: string
}

export interface CodeBlock extends BlockBase {
  type: 'code'
  language: string
  code: string
  caption?: string
}

export interface TableBlock extends BlockBase {
  type: 'table'
  headers: string[]
  /** 每行长度必须与 headers 一致(normalize 阶段截齐/补空) */
  rows: string[][]
  caption?: string
}

export type ChartType = 'bar' | 'line' | 'pie'

export interface ChartSeries {
  name: string
  data: number[]
}

export interface ChartBlock extends BlockBase {
  type: 'chart'
  chartType: ChartType
  categories: string[]
  /** pie 图仅使用第一个 series */
  series: ChartSeries[]
  caption?: string
}

/** 强调块:一句大号强调文字(结论、金句、关键数字)+ 可选说明;不是图,配图用 image 块 */
export interface EmphasisBlock extends BlockBase {
  type: 'emphasis'
  text: string
  caption?: string
}

/**
 * 图片块:src 为课件自有存储里的图片对象键(生成配图或取用的素材图片),
 * width/height 为原图像素尺寸,布局按原始宽高比排版(高度封顶)。
 * 播放地址不在文档里:视图另给「对象键 → 短期预签名地址」映射(assetUrls)。
 */
export interface ImageBlock extends BlockBase {
  type: 'image'
  src: string
  width: number
  height: number
  caption?: string
}

export type CalloutVariant = 'info' | 'tip' | 'warning' | 'conclusion'

export interface CalloutBlock extends BlockBase {
  type: 'callout'
  variant: CalloutVariant
  title?: string
  text: string
}

export interface QuizOption {
  label: string
  text: string
}

/** 选择题块,仅允许出现在 type = "quiz" 的页面上 */
export interface QuizChoiceBlock extends BlockBase {
  type: 'quiz_choice'
  stem: string
  options: QuizOption[]
  /** 正确选项 label 列表;单选题长度为 1 */
  answer: string[]
  multiple: boolean
  explanation: string
}

/** 可放入 columns 的叶子块(不含 columns 自身与 quiz_choice) */
export type LeafBlock =
  | HeadingBlock
  | ParagraphBlock
  | BulletsBlock
  | FormulaBlock
  | CodeBlock
  | TableBlock
  | ChartBlock
  | EmphasisBlock
  | ImageBlock
  | CalloutBlock

export interface ColumnsBlock extends BlockBase {
  type: 'columns'
  /** 各列宽度占比,如 [7, 5];缺省均分。长度必须等于 children 列数 */
  ratio?: number[]
  /** 每列一个叶子块数组,2 列为主(布局引擎最多支持 3 列) */
  children: LeafBlock[][]
}

export type Block = LeafBlock | ColumnsBlock | QuizChoiceBlock

export type BlockType = Block['type']

export const BLOCK_TYPES: readonly BlockType[] = [
  'heading',
  'paragraph',
  'bullets',
  'formula',
  'code',
  'table',
  'chart',
  'emphasis',
  'image',
  'callout',
  'columns',
  'quiz_choice',
] as const

export const BLOCK_TYPE_LABELS: Record<BlockType, string> = {
  heading: '标题',
  paragraph: '段落',
  bullets: '要点',
  formula: '公式',
  code: '代码',
  table: '表格',
  chart: '图表',
  emphasis: '强调',
  image: '图片',
  callout: '强调框',
  columns: '分栏',
  quiz_choice: '选择题',
}
