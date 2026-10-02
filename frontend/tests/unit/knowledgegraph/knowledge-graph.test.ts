import { describe, expect, it } from 'vitest'

import type { EdgeView, NodeView } from '@/api/generated'
import {
  ancestorIds,
  buildTree,
  categoryOf,
  previewItems,
  relationsOf,
  snapshotItems,
  unitPath,
} from '@/features/knowledgegraph/knowledgeGraph'

function node(
  id: number,
  parentId: number | null,
  position: number,
  kind: NodeView['kind'],
  label: string,
  kpType: NodeView['kpType'] = kind === 'knowledge_point' ? '概念' : null,
): NodeView {
  return {
    id,
    parentId,
    position,
    kind,
    kpType,
    label,
    summary: null,
    definition: null,
    explanation: null,
    aliases: [],
    code: null,
    language: null,
    sourceSectionTitle: null,
    quote: null,
    resources: [],
  }
}

function edge(id: number, source: number, target: number, kind: EdgeView['kind']): EdgeView {
  return { id, sourceNodeId: source, targetNodeId: target, kind, evidence: null }
}

const NODES = [
  node(1, null, 1, 'unit', '第一章'),
  node(2, 1, 2, 'knowledge_point', '变量', '概念'),
  node(3, 1, 1, 'unit', '1.1 节'),
  node(4, 3, 1, 'knowledge_point', '赋值', '方法'),
  node(5, 2, 1, 'code_example', '示例'),
  node(6, null, 2, 'unit', '第二章'),
  node(7, 6, 1, 'knowledge_point', '循环', '技能'),
]

describe('知识图谱树模型', () => {
  it('根是图谱本身,子节点按 position 排序,父节点缺失的挂在根下', () => {
    const tree = buildTree('光学', snapshotItems([...NODES, node(9, 99, 1, 'knowledge_point', '孤儿')]))
    expect(tree.id).toBe('')
    expect(tree.label).toBe('光学')
    expect(tree.children.map((child) => child.label)).toEqual(['第一章', '孤儿', '第二章'])
    expect(tree.children[0]?.children.map((child) => child.label)).toEqual(['1.1 节', '变量'])
    expect(tree.children[0]?.children[1]?.children.map((child) => child.label)).toEqual(['示例'])
  })

  it('祖先链从顶层到父节点;章节路径只取标签', () => {
    expect(ancestorIds(snapshotItems(NODES), '5')).toEqual(['1', '2'])
    expect(ancestorIds(snapshotItems(NODES), '1')).toEqual([])
    expect(unitPath(NODES, 4)).toEqual(['第一章', '1.1 节'])
  })

  it('类别:章节 / 小类 / 代码示例', () => {
    expect(categoryOf(NODES[0] as NodeView)).toBe('章节')
    expect(categoryOf(NODES[3] as NodeView)).toBe('方法')
    expect(categoryOf(NODES[4] as NodeView)).toBe('代码示例')
  })

  it('预览节点也能画成导图', () => {
    const items = previewItems([
      {
        key: 'u0',
        parentKey: null,
        position: 1,
        kind: 'unit',
        kpType: null,
        label: '章',
        summary: null,
        definition: null,
        explanation: null,
        aliases: [],
        code: null,
        language: null,
        sourceSectionTitle: null,
        quote: null,
      },
      {
        key: 'k0',
        parentKey: 'u0',
        position: 1,
        kind: 'knowledge_point',
        kpType: '规则',
        label: '点',
        summary: null,
        definition: null,
        explanation: null,
        aliases: [],
        code: null,
        language: null,
        sourceSectionTitle: null,
        quote: null,
      },
    ])
    expect(buildTree('教材', items).children[0]?.children[0]?.category).toBe('规则')
  })
})

describe('语义关系', () => {
  const EDGES = [
    edge(1, 2, 4, 'prerequisite'),
    edge(2, 4, 7, 'prerequisite'),
    edge(3, 2, 7, 'related'),
  ]

  it('关系按方向分组:前置 / 后续 / 相关', () => {
    const relations = relationsOf(4, EDGES)
    expect(relations.prerequisites.map((item) => item.sourceNodeId)).toEqual([2])
    expect(relations.successors.map((item) => item.targetNodeId)).toEqual([7])
    expect(relations.related).toEqual([])
    expect(relationsOf(7, EDGES).related.map((item) => item.id)).toEqual([3])
  })
})
