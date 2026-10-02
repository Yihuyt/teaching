import { computed, ref, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { AxiosResponse } from 'axios'

import { ApiProblem, api, errorMessage } from '@/api/client'
import type {
  AttachResourceRequest,
  CreateEdgeRequest,
  CreateNodeRequest,
  GraphSnapshot,
  NodeView,
  UpdateNodeRequest,
} from '@/api/generated'
import type { ResourceItemType } from '@/features/knowledgegraph/knowledgeGraph'

/**
 * 图谱编辑状态:快照 + 选中 + 忙态。每个操作即时落库并用返回的快照替换本地状态;
 * 基于过期视图的操作被服务端以 404 拒绝(节点已被删除)时提示并重载快照,其他错误按后端文案提示。
 */
export function useGraphEditor(courseId: Ref<number>, graphId: Ref<number>) {
  const loading = ref(true)
  const loadError = ref('')
  const snapshot = ref<GraphSnapshot>()
  const selectedId = ref<number | null>(null)
  const busy = ref(false)

  const graph = computed(() => snapshot.value?.graph)
  const nodes = computed(() => snapshot.value?.nodes ?? [])
  const edges = computed(() => snapshot.value?.edges ?? [])
  const selected = computed<NodeView | null>(
    () => nodes.value.find((node) => node.id === selectedId.value) ?? null,
  )

  async function load(): Promise<void> {
    loading.value = true
    loadError.value = ''
    try {
      snapshot.value = (
        await api.courseKnowledgeGraphSnapshot(courseId.value, graphId.value, { management: true })
      ).data
      if (selectedId.value !== null && !nodes.value.some((node) => node.id === selectedId.value)) {
        selectedId.value = null
      }
    } catch (error: unknown) {
      loadError.value = errorMessage(error)
    } finally {
      loading.value = false
    }
  }

  function select(id: number | null): void {
    selectedId.value = id
  }

  async function run(operation: () => Promise<AxiosResponse<GraphSnapshot>>): Promise<boolean> {
    if (busy.value) return false
    busy.value = true
    try {
      snapshot.value = (await operation()).data
      if (selectedId.value !== null && !nodes.value.some((node) => node.id === selectedId.value)) {
        selectedId.value = null
      }
      return true
    } catch (error: unknown) {
      if (error instanceof ApiProblem && error.status === 404) {
        ElMessage.warning(error.message)
        await load()
      } else {
        ElMessage.error(errorMessage(error))
      }
      return false
    } finally {
      busy.value = false
    }
  }

  async function createNode(request: CreateNodeRequest): Promise<number | null> {
    if (busy.value) return null
    busy.value = true
    try {
      const created = (await api.courseKnowledgeGraphCreateNode(courseId.value, graphId.value, request)).data
      snapshot.value = created.snapshot
      selectedId.value = created.createdNodeId
      return created.createdNodeId
    } catch (error: unknown) {
      if (error instanceof ApiProblem && error.status === 404) {
        ElMessage.warning(error.message)
        await load()
      } else {
        ElMessage.error(errorMessage(error))
      }
      return null
    } finally {
      busy.value = false
    }
  }

  function updateNode(nodeId: number, request: UpdateNodeRequest): Promise<boolean> {
    return run(() => api.courseKnowledgeGraphUpdateNode(courseId.value, graphId.value, nodeId, request))
  }

  async function deleteNode(nodeId: number): Promise<boolean> {
    const parentId = nodes.value.find((node) => node.id === nodeId)?.parentId ?? null
    const ok = await run(() => api.courseKnowledgeGraphDeleteNode(courseId.value, graphId.value, nodeId))
    if (ok) selectedId.value = parentId
    return ok
  }

  function createEdge(request: CreateEdgeRequest): Promise<boolean> {
    return run(() => api.courseKnowledgeGraphCreateEdge(courseId.value, graphId.value, request))
  }

  function deleteEdge(edgeId: number): Promise<boolean> {
    return run(() => api.courseKnowledgeGraphDeleteEdge(courseId.value, graphId.value, edgeId))
  }

  function attachResource(nodeId: number, request: AttachResourceRequest): Promise<boolean> {
    return run(() => api.courseKnowledgeGraphAttachResource(courseId.value, graphId.value, nodeId, request))
  }

  function detachResource(nodeId: number, itemType: ResourceItemType, contentId: number): Promise<boolean> {
    return run(() =>
      api.courseKnowledgeGraphDetachResource(courseId.value, graphId.value, nodeId, itemType, contentId),
    )
  }

  return {
    loading,
    loadError,
    snapshot,
    graph,
    nodes,
    edges,
    selectedId,
    selected,
    busy,
    load,
    select,
    createNode,
    updateNode,
    deleteNode,
    createEdge,
    deleteEdge,
    attachResource,
    detachResource,
  }
}
