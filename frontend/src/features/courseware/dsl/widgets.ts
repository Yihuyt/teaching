/**
 * 交互页组件类型与规格 —— 交互页(interactive)的结构化依据,随页落库(interactive.widgetType / widgetOutline)。
 * 内容生成按类型路由到专属模板。
 */
export type WidgetType = 'simulation' | 'diagram' | 'game' | 'visualization3d'

export const WIDGET_TYPES: readonly WidgetType[] = [
  'simulation',
  'diagram',
  'game',
  'visualization3d',
]

export const WIDGET_TYPE_LABELS: Record<WidgetType, string> = {
  simulation: '参数仿真',
  diagram: '交互图解',
  game: '教学游戏',
  visualization3d: '3D 可视化',
}

/**
 * 组件规格(规格先行):松散对象——允许模型补充额外描述字段,生成模板只读取已知字段。
 */
export interface WidgetOutline {
  /** simulation:核心概念名 */
  concept?: string
  /** simulation:学生可调节的变量 */
  keyVariables?: string[]
  /** diagram:图解形态 */
  diagramType?: 'flowchart' | 'mindmap' | 'hierarchy' | 'system'
  /** diagram:节点数上限 */
  nodeCount?: number
  /** diagram:指定节点清单(给了就必须原样全用) */
  nodes?: { id: string; label: string; parentId?: string; icon?: string; details?: string }[]
  /** game:玩法类型(优先 action,禁把选择题包装成游戏) */
  gameType?: 'action' | 'puzzle' | 'strategy' | 'card' | 'quiz'
  /** game:玩家要「做」什么 */
  challenge?: string
  /** game:玩家控制的对象 */
  playerControls?: string[]
  /** visualization3d:场景类型 */
  visualizationType?: 'molecular' | 'solar' | 'anatomy' | 'geometry' | 'physics' | 'custom'
  /** visualization3d:3D 对象列表 */
  objects?: string[]
  /** visualization3d:交互控件列表 */
  interactions?: string[]
  [extra: string]: unknown
}
