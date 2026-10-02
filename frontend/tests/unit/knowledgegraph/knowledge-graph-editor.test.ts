import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosResponse } from 'axios'

import type { GraphSnapshot, NodeView } from '@/api/generated'

const apiMock = vi.hoisted(() => ({
  courseKnowledgeGraphSnapshot: vi.fn(),
  courseKnowledgeGraphCreateNode: vi.fn(),
  courseKnowledgeGraphDeleteNode: vi.fn(),
  courseKnowledgeGraphUpdateNode: vi.fn(),
}))
const messageMock = vi.hoisted(() => ({ warning: vi.fn(), error: vi.fn() }))

vi.mock('element-plus', () => ({ ElMessage: messageMock }))
vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/client')>()
  return { ...actual, api: apiMock }
})

import { ApiProblem } from '@/api/client'
import { useGraphEditor } from '@/features/knowledgegraph/useGraphEditor'

function problem(status: number, detail: string): ApiProblem {
  return new ApiProblem({ type: 'urn:teaching:problem:domain-error', title: '错误', status, detail })
}

function node(id: number, parentId: number | null, label: string): NodeView {
  return {
    id,
    parentId,
    position: 1,
    kind: 'unit',
    kpType: null,
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

function snapshot(nodes: NodeView[]): GraphSnapshot {
  return {
    graph: { id: 8, courseId: 3, name: '图谱', published: true, createdAt: '', updatedAt: '' },
    nodes,
    edges: [],
  }
}

function response<T>(data: T): AxiosResponse<T> {
  return { data } as AxiosResponse<T>
}

describe('useGraphEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('加载快照;新建节点后用返回快照替换并选中新节点', async () => {
    apiMock.courseKnowledgeGraphSnapshot.mockResolvedValue(response(snapshot([node(1, null, '章')])))
    apiMock.courseKnowledgeGraphCreateNode.mockResolvedValue(
      response({ createdNodeId: 2, snapshot: snapshot([node(1, null, '章'), node(2, 1, '点')]) }),
    )
    const editor = useGraphEditor(ref(3), ref(8))

    await editor.load()
    expect(editor.nodes.value).toHaveLength(1)
    const created = await editor.createNode({
      parentId: 1,
      kind: 'knowledge_point',
      label: '点',
      kpType: '概念',
      summary: null,
      definition: null,
      explanation: null,
      aliases: [],
      code: null,
      language: null,
      sourceSectionTitle: null,
      quote: null,
    })

    expect(created).toBe(2)
    expect(editor.nodes.value).toHaveLength(2)
    expect(editor.selected.value?.label).toBe('点')
    expect(editor.busy.value).toBe(false)
  })

  it('服务端 404(节点已被删除)时提示并重载快照,选中项随之清空', async () => {
    apiMock.courseKnowledgeGraphSnapshot
      .mockResolvedValueOnce(response(snapshot([node(1, null, '章'), node(2, 1, '点')])))
      .mockResolvedValueOnce(response(snapshot([node(1, null, '章')])))
    apiMock.courseKnowledgeGraphUpdateNode.mockRejectedValue(problem(404, '节点已被删除，请刷新'))
    const editor = useGraphEditor(ref(3), ref(8))
    await editor.load()
    editor.select(2)

    const ok = await editor.updateNode(2, {
      label: '新名',
      kpType: null,
      summary: '改名',
      definition: null,
      explanation: null,
      aliases: [],
      code: null,
      language: null,
      sourceSectionTitle: null,
      quote: null,
    })

    expect(ok).toBe(false)
    expect(messageMock.warning).toHaveBeenCalledWith('节点已被删除，请刷新')
    expect(apiMock.courseKnowledgeGraphSnapshot).toHaveBeenCalledTimes(2)
    expect(editor.nodes.value).toHaveLength(1)
    expect(editor.selected.value).toBeNull()
  })

  it('删除节点后选中它的父节点;其他错误按后端文案提示', async () => {
    apiMock.courseKnowledgeGraphSnapshot.mockResolvedValue(
      response(snapshot([node(1, null, '章'), node(2, 1, '点')])),
    )
    apiMock.courseKnowledgeGraphDeleteNode.mockResolvedValueOnce(response(snapshot([node(1, null, '章')])))
    const editor = useGraphEditor(ref(3), ref(8))
    await editor.load()
    editor.select(2)

    expect(await editor.deleteNode(2)).toBe(true)
    expect(editor.selected.value?.id).toBe(1)

    apiMock.courseKnowledgeGraphDeleteNode.mockRejectedValueOnce(problem(409, '单个图谱最多 5000 个节点'))
    expect(await editor.deleteNode(1)).toBe(false)
    expect(messageMock.error).toHaveBeenCalledWith('单个图谱最多 5000 个节点')
  })
})
