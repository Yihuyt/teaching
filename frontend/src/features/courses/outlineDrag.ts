import { reactive } from 'vue'

/**
 * 课程内容拖拽排序的进行中状态(响应式:顶层空落点要在拖拽开始时出现)。
 * 放在模块里而不是组件 <script setup> 内:
 * setup 里的顶层变量是每个单元节点实例各一份,而拖拽的源节点与目标节点是不同实例;
 * dragover 阶段又读不到 dataTransfer 的数据,所以要一个跨实例共享的位置。
 */
type OutlineDragPayload =
  { kind: 'unit'; id: number; parentId: number | null } | { kind: 'item'; id: number; unitId: number | null }

export const outlineDrag = reactive<{ current: OutlineDragPayload | null }>({ current: null })
