/**
 * 课件的编辑操作词汇表 —— 教师手工编辑的每个改动都是其中一条,
 * 服务端逐条校验(目标存在、结果通过文档校验)后整批原子应用并即时落库。
 * 与后端 courseware/domain/EditOp.java 一一对应。
 */
import type { Block } from '@/features/courseware/dsl/blocks.ts'
import type { Action } from '@/features/courseware/dsl/actions.ts'
import type { BlockSize, SceneType, PresetName } from '@/features/courseware/dsl/stage.ts'
import type { WidgetType } from '@/features/courseware/dsl/widgets.ts'

/** 编辑操作里的讲稿段(刻意不含音频字段——音频归服务端管理,文本未变的段保留原音频) */
export interface EditSpeechSegment {
  text: string
  actions: Action[]
}

export interface UpdateStageMetaOp {
  op: 'update_stage_meta'
  title: string
}

/** 新增一页(讲解页 / 测验页):blocks 是这页的初始内容(至少一块);交互页走 add_interactive_scene */
export interface AddSceneOp {
  op: 'add_scene'
  /** 插入位置(0 起,越界夹紧到末尾) */
  index: number
  title: string
  type: Exclude<SceneType, 'interactive' | 'video'>
  preset: PresetName
  summary?: string
  blocks: Block[]
}

/**
 * 新增一个交互页:html 是教师自己准备的自包含网页(单文档、不联网取数、不嵌套文档、外部资源只许白名单 CDN),
 * 服务端校验后做 KaTeX 后处理落库;widgetType 只作标注
 */
export interface AddInteractiveSceneOp {
  op: 'add_interactive_scene'
  index: number
  title: string
  summary?: string
  html: string
  widgetType?: WidgetType
}

/** 新增一个视频页:src 是上传到本课件名下的视频对象键(POST /videos 的返回) */
export interface AddVideoSceneOp {
  op: 'add_video_scene'
  index: number
  title: string
  summary?: string
  src: string
}

export interface DeleteSceneOp {
  op: 'delete_scene'
  sceneId: string
}

export interface MoveSceneOp {
  op: 'move_scene'
  sceneId: string
  toIndex: number
}

/** 修改页面元信息(type 不可改——类型转换请用 delete_scene + add_scene) */
export interface UpdateSceneMetaOp {
  op: 'update_scene_meta'
  sceneId: string
  title?: string
  preset?: PresetName
  summary?: string
}

/** 在页内新增块(interactive 页不支持块操作) */
export interface AddBlockOp {
  op: 'add_block'
  sceneId: string
  /** 插入位置(0 起,越界夹紧到末尾) */
  index: number
  block: Block
}

/** 整块替换(id 以 block.id 为准,可与原 id 不同,但不得与他块撞车) */
export interface ReplaceBlockOp {
  op: 'replace_block'
  sceneId: string
  blockId: string
  block: Block
}

export interface DeleteBlockOp {
  op: 'delete_block'
  sceneId: string
  blockId: string
}

export interface MoveBlockOp {
  op: 'move_block'
  sceneId: string
  blockId: string
  toIndex: number
}

/** 整页讲稿替换(动作目标经服务端校验;文本与原段相同的段保留原音频) */
export interface SetSpeechOp {
  op: 'set_speech'
  sceneId: string
  speech: EditSpeechSegment[]
}

/** 整份换掉交互页的网页(讲稿与组件标注保留) */
export interface SetInteractiveHtmlOp {
  op: 'set_interactive_html'
  sceneId: string
  html: string
}

/** 换掉视频页的视频(讲稿保留) */
export interface SetVideoOp {
  op: 'set_video'
  sceneId: string
  src: string
}

/**
 * 钉住一个顶层块:位置与宽度由教师定,image / chart 还要给高度(其余块高度由内容量出,不能给 h)。
 * 已钉住的块再次 pin 即移动 / 改尺寸;字号档不受影响。
 */
export interface PinBlockOp {
  op: 'pin_block'
  sceneId: string
  blockId: string
  x: number
  y: number
  w: number
  h?: number
}

/** 解除钉住,块回到流式排版(字号档保留) */
export interface UnpinBlockOp {
  op: 'unpin_block'
  sceneId: string
  blockId: string
}

/** 改一个顶层块的字号档;normal 即恢复缺省 */
export interface SetBlockSizeOp {
  op: 'set_block_size'
  sceneId: string
  blockId: string
  size: BlockSize | 'normal'
}

export type EditOp =
  | UpdateStageMetaOp
  | AddSceneOp
  | AddInteractiveSceneOp
  | AddVideoSceneOp
  | DeleteSceneOp
  | MoveSceneOp
  | UpdateSceneMetaOp
  | AddBlockOp
  | ReplaceBlockOp
  | DeleteBlockOp
  | MoveBlockOp
  | SetSpeechOp
  | SetInteractiveHtmlOp
  | SetVideoOp
  | PinBlockOp
  | UnpinBlockOp
  | SetBlockSizeOp

export function toEditSpeech(speech: { text: string; actions: Action[] }[]): EditSpeechSegment[] {
  return speech.map(({ text, actions }) => ({ text, actions }))
}
