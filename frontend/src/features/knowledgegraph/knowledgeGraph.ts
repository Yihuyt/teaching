import type { EdgeView, NodeResourceView, NodeView, PreviewNode } from '@/api/generated'

export type NodeKind = NodeView['kind']
export type KpType = NonNullable<NodeView['kpType']>
export type EdgeKind = EdgeView['kind']
export type ResourceItemType = NodeResourceView['itemType']

export const NODE_KIND_LABELS: Record<NodeKind, string> = {
  unit: '章节',
  knowledge_point: '知识点',
  code_example: '代码示例',
}

export const KP_TYPES: KpType[] = ['概念', '方法', '技能', '规则', '工具', '易错点']

export const EDGE_KIND_LABELS: Record<EdgeKind, string> = {
  prerequisite: '前置',
  related: '相关',
}

export const RESOURCE_TYPE_LABELS: Record<ResourceItemType, string> = {
  material: '文件',
  question: '试题',
  programming_problem: '编程题',
}

export const NODE_TEXT_LABELS: Record<NodeKind, string> = {
  unit: '摘要',
  knowledge_point: '释义',
  code_example: '说明',
}

export function resourceKey(itemType: ResourceItemType, contentId: number): string {
  return `${itemType}:${contentId}`
}

export function categoryOf(node: Pick<NodeView, 'kind' | 'kpType'>): string {
  if (node.kind === 'knowledge_point') return node.kpType ?? '知识点'
  return NODE_KIND_LABELS[node.kind]
}

const CATEGORY_COLORS: Record<string, string> = {
  章节: '#e8871e',
  概念: '#e0509b',
  方法: '#7c5cff',
  技能: '#12a480',
  规则: '#d94848',
  工具: '#0e9fd8',
  易错点: '#c2571b',
  知识点: '#e0509b',
  代码示例: '#5b6472',
}

export function categoryColor(category: string): string {
  return CATEGORY_COLORS[category] ?? '#4c72b0'
}

/** 导图节点:快照节点与构建预览节点都能转成它 */
export interface MindMapItem {
  id: string
  parentId: string | null
  position: number
  label: string
  category: string
}

export function snapshotItems(nodes: NodeView[]): MindMapItem[] {
  return nodes.map((node) => ({
    id: String(node.id),
    parentId: node.parentId === null ? null : String(node.parentId),
    position: node.position,
    label: node.label,
    category: categoryOf(node),
  }))
}

export function previewItems(nodes: PreviewNode[]): MindMapItem[] {
  return nodes.map((node) => ({
    id: node.key,
    parentId: node.parentKey,
    position: node.position,
    label: node.label,
    category: categoryOf(node),
  }))
}

export interface TreeNode {
  id: string
  label: string
  category: string
  children: TreeNode[]
}

/** 树模型:根 = 图谱本身(id 为空串);同父按 position 升序;父节点缺失的节点挂在根下 */
export function buildTree(rootLabel: string, items: MindMapItem[]): TreeNode {
  const byId = new Map<string, TreeNode>()
  for (const item of items) {
    byId.set(item.id, { id: item.id, label: item.label, category: item.category, children: [] })
  }
  const root: TreeNode = { id: '', label: rootLabel, category: '图谱', children: [] }
  const sorted = items.toSorted((a, b) => a.position - b.position)
  for (const item of sorted) {
    const node = byId.get(item.id) as TreeNode
    const parent = item.parentId === null ? undefined : byId.get(item.parentId)
    ;(parent ?? root).children.push(node)
  }
  return root
}

/** 从顶层到父节点的祖先 id 链(不含自身);父链断裂即停 */
export function ancestorIds(items: MindMapItem[], id: string): string[] {
  const parentOf = new Map(items.map((item) => [item.id, item.parentId]))
  const chain: string[] = []
  const seen = new Set<string>([id])
  let cursor = parentOf.get(id) ?? null
  while (cursor !== null && !seen.has(cursor)) {
    chain.unshift(cursor)
    seen.add(cursor)
    cursor = parentOf.get(cursor) ?? null
  }
  return chain
}

/** 章节路径解析器:索引只建一次,批量求路径时不再逐节点重建 */
export function unitPathResolver(nodes: NodeView[]): (nodeId: number) => string[] {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  return (nodeId) => {
    const path: string[] = []
    const seen = new Set<number>([nodeId])
    let cursor = byId.get(nodeId)?.parentId ?? null
    while (cursor !== null && !seen.has(cursor)) {
      const parent = byId.get(cursor)
      if (!parent) break
      path.unshift(parent.label)
      seen.add(cursor)
      cursor = parent.parentId
    }
    return path
  }
}

export function unitPath(nodes: NodeView[], nodeId: number): string[] {
  return unitPathResolver(nodes)(nodeId)
}

interface NodeRelations {
  prerequisites: EdgeView[]
  successors: EdgeView[]
  related: EdgeView[]
}

export function relationsOf(nodeId: number, edges: EdgeView[]): NodeRelations {
  const relations: NodeRelations = { prerequisites: [], successors: [], related: [] }
  for (const edge of edges) {
    if (edge.kind === 'related') {
      if (edge.sourceNodeId === nodeId || edge.targetNodeId === nodeId) relations.related.push(edge)
    } else if (edge.targetNodeId === nodeId) {
      relations.prerequisites.push(edge)
    } else if (edge.sourceNodeId === nodeId) {
      relations.successors.push(edge)
    }
  }
  return relations
}

export function otherEnd(edge: EdgeView, nodeId: number): number {
  return edge.sourceNodeId === nodeId ? edge.targetNodeId : edge.sourceNodeId
}
