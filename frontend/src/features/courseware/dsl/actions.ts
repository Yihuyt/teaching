/**
 * 讲课动作 —— 播放引擎在朗读某个讲稿段落时要执行的视觉指令。
 *
 * 时序模型:动作挂在 SpeechSegment 上,在该段音频**开始播放时**依序触发,
 * 不做段内时间戳对齐(音频 ended 事件是唯一时钟)。
 */

/**
 * 动作目标:块 id(`blk-bullets-1`),bullets 子条目可用 `blk-bullets-1#2`
 * 定位第 2 条(1 起数)。
 */
export type ActionTarget = string

export interface HighlightAction {
  type: 'highlight'
  target: ActionTarget
}

/** 显示此前隐藏的块(用于逐步呈现;未被任何 reveal 引用的块默认可见) */
export interface RevealAction {
  type: 'reveal'
  target: ActionTarget
}

export interface PauseAction {
  type: 'pause'
  /** 朗读完本段后追加的停顿毫秒数,范围 [200, 5000] */
  ms: number
}

export type Action = HighlightAction | RevealAction | PauseAction

export type ActionType = Action['type']

export interface SpeechSegment {
  text: string
  actions: Action[]
  /** TTS 阶段回填,音频对象键(是否已合成以此为准);播放地址由视图的 assetUrls 映射给出 */
  audioPath?: string
}
