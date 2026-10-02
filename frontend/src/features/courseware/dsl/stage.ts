import type { Block } from '@/features/courseware/dsl/blocks.ts'
import type { SpeechSegment } from '@/features/courseware/dsl/actions.ts'
import type { WidgetOutline, WidgetType } from '@/features/courseware/dsl/widgets.ts'

export type SceneType = 'content' | 'quiz' | 'interactive' | 'video'

/** 没有内容块的页面类型:整页是一份网页或一个视频,讲稿只允许 pause,没有排版覆盖 */
export function isBlocklessScene(type: SceneType): boolean {
  return type === 'interactive' || type === 'video'
}

/**
 * 布局预设:决定页面的区域框架(标题条 + 内容区),
 * 具体矩形定义在 layout 包的 presets 中。
 */
export type PresetName =
  'title-cover' | 'standard' | 'two-column' | 'media-right' | 'quiz' | 'section-divider'

export const PRESET_NAMES: readonly PresetName[] = [
  'title-cover',
  'standard',
  'two-column',
  'media-right',
  'quiz',
  'section-divider',
] as const

export const PRESET_LABELS: Record<PresetName, string> = {
  'title-cover': '封面',
  standard: '标准单栏',
  'two-column': '双栏',
  'media-right': '左文右媒',
  quiz: '测验',
  'section-divider': '章节页',
}

export interface InteractiveContent {
  /** 自包含 HTML 文档(内联 CSS/JS),学生自主操作,无外部遥控 */
  html: string
  /** 组件类型(生成时定死;缺省表示按通用交互模板生成) */
  widgetType?: WidgetType | null
  /** 组件规格(松散对象;与 widgetType 一起随页落库,是重做这页的结构化依据) */
  widgetOutline?: WidgetOutline | null
}

/** 块字号档:在主题字号阶梯之上对整块的倍率;缺省即 1(不写) */
export type BlockSize = 'small' | 'large' | 'xlarge'

export const BLOCK_SIZES: readonly BlockSize[] = ['small', 'large', 'xlarge'] as const

/** 字号档 → 倍率(排版引擎与渲染共用;Java Theme 同值) */
export const BLOCK_SIZE_SCALES: Record<BlockSize, number> = {
  small: 0.85,
  large: 1.2,
  xlarge: 1.45,
}

export const BLOCK_SIZE_LABELS: Record<BlockSize | 'normal', string> = {
  small: '小',
  normal: '标准',
  large: '大',
  xlarge: '特大',
}

/**
 * 钉住帧:教师在画布上定下的位置与宽度(1280×720 逻辑像素)。
 * 高度只对 image / chart 这类"高度不由内容决定"的块存在;文字类块的高度由内容在该宽度下量出。
 */
export interface PinFrame {
  x: number
  y: number
  w: number
  h?: number
}

/**
 * 一个顶层块的排版覆盖:钉住帧与/或字号档,缺的那项仍由引擎决定。
 * 只能引用页面顶层块(columns 内的子块不能单独覆盖);columns 只能改字号档、不能钉住。
 * 被钉住的块不参与流式排版与页级缩字,其余块在区域内绕开它上下环绕。
 */
export interface BlockLayout {
  blockId: string
  frame?: PinFrame
  size?: BlockSize
}

/** 钉住帧的最小宽 / 高(逻辑像素) */
export const MIN_PIN_WIDTH = 120
export const MIN_PIN_HEIGHT = 60

/** 钉住时高度由教师决定(而非内容量出)的块类型 */
export const FREE_HEIGHT_TYPES: ReadonlySet<Block['type']> = new Set(['image', 'chart'])

export interface Scene {
  id: string
  type: SceneType
  title: string
  preset: PresetName
  /** 本页要讲什么,可不填;AI 重做这页时以它为依据 */
  summary?: string
  /** interactive 页为空数组(内容在 interactive.html 里) */
  blocks: Block[]
  speech: SpeechSegment[]
  /** 教师手工排版覆盖(按顶层块 id 引用;缺省 / 空数组即全自动排版) */
  layouts?: BlockLayout[]
  /** 仅 type = "interactive" 时存在 */
  interactive?: InteractiveContent
  /** 仅 type = "video" 时存在 */
  video?: VideoContent
}

/** 视频页内容:教师上传的视频,src 为课件名下的对象键(播放地址经 assetUrls 解析) */
export interface VideoContent {
  src: string
}

export type ThemeName = 'default'

/**
 * 课件文档内容。身份与时间戳由数据库行持有,不在文档里重复;
 * 列表/详情视图的 id、版本号等元数据由接口层单独下发。
 */
export interface Stage {
  title: string
  theme: ThemeName
  scenes: Scene[]
}
