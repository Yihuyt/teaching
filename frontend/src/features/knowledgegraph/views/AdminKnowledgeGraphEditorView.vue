<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'
import type { EdgeView, NodeResourceView, NodeView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import EdgeDialog from '@/features/knowledgegraph/components/EdgeDialog.vue'
import GraphMindMap from '@/features/knowledgegraph/components/GraphMindMap.vue'
import NodeFormDialog, { type NodeFormValue } from '@/features/knowledgegraph/components/NodeFormDialog.vue'
import NodeInspector from '@/features/knowledgegraph/components/NodeInspector.vue'
import ResourceAttachDialog from '@/features/knowledgegraph/components/ResourceAttachDialog.vue'
import { useGraphEditor } from '@/features/knowledgegraph/useGraphEditor'
import { INSPECTOR_MAX, INSPECTOR_MIN, useInspectorWidth } from '@/features/knowledgegraph/useInspectorWidth'
import { confirm } from '@/shared/dialogs'
import {
  KP_TYPES,
  NODE_KIND_LABELS,
  categoryOf,
  otherEnd,
  relationsOf,
  snapshotItems,
  type EdgeKind,
  type NodeKind,
  resourceKey,
} from '@/features/knowledgegraph/knowledgeGraph'
import type { LibraryContent } from '@/features/courses/library'

const route = useRoute()
const router = useRouter()
const courseId = computed(() => Number(route.params.courseId))
const graphId = computed(() => Number(route.params.graphId))
const editor = useGraphEditor(courseId, graphId)

const search = ref('')
const categoryFilter = ref<string[]>([])
const mindMap = ref<InstanceType<typeof GraphMindMap>>()
const inspectorWidth = useInspectorWidth()

const categories = computed(() => ['章节', ...KP_TYPES, '代码示例'])
const items = computed(() => snapshotItems(editor.nodes.value))
const selectedKey = computed(() =>
  editor.selectedId.value === null ? null : String(editor.selectedId.value),
)

const highlights = computed(() => {
  const node = editor.selected.value
  if (!node || node.kind !== 'knowledge_point') return {}
  const relations = relationsOf(node.id, editor.edges.value)
  const marks: Record<string, 'prerequisite' | 'successor' | 'related'> = {}
  relations.prerequisites.forEach((edge) => (marks[String(otherEnd(edge, node.id))] = 'prerequisite'))
  relations.successors.forEach((edge) => (marks[String(otherEnd(edge, node.id))] = 'successor'))
  relations.related.forEach((edge) => (marks[String(otherEnd(edge, node.id))] = 'related'))
  return marks
})

function selectKey(key: string | null): void {
  editor.select(key === null ? null : Number(key))
}

async function selectAndReveal(id: number): Promise<void> {
  editor.select(id)
  await nextTick()
  mindMap.value?.reveal(String(id))
}

function searchNode(): void {
  const keyword = search.value.trim()
  if (!keyword) return
  const hit = editor.nodes.value.find((node) => node.label.includes(keyword))
  if (!hit) {
    ElMessage.info('没有匹配的节点')
    return
  }
  void selectAndReveal(hit.id)
}

const formVisible = ref(false)
const formKind = ref<NodeKind>('unit')
const formInitial = ref<NodeView | null>(null)
const formParentId = ref<number | null>(null)

function openAddChild(kind: NodeKind): void {
  formKind.value = kind
  formInitial.value = null
  formParentId.value = editor.selected.value?.id ?? null
  formVisible.value = true
}

function openEdit(): void {
  const node = editor.selected.value
  if (!node) return
  formKind.value = node.kind
  formInitial.value = node
  formVisible.value = true
}

async function submitForm(value: NodeFormValue): Promise<void> {
  const payload = {
    label: value.label,
    kpType: value.kpType,
    summary: value.summary,
    definition: value.definition,
    explanation: value.explanation,
    aliases: value.aliases,
    code: value.code,
    language: value.language,
    sourceSectionTitle: value.sourceSectionTitle,
    quote: value.quote,
  }
  if (formInitial.value) {
    if (await editor.updateNode(formInitial.value.id, payload)) formVisible.value = false
    return
  }
  const created = await editor.createNode({ parentId: formParentId.value, kind: formKind.value, ...payload })
  if (created !== null) {
    formVisible.value = false
    await nextTick()
    mindMap.value?.reveal(String(created))
  }
}

async function removeNode(): Promise<void> {
  const node = editor.selected.value
  if (!node) return
  // 与全局删除确认同一口径:有子节点 / 有关系 / 有挂载即"有关联",不罗列明细
  const associated =
    editor.nodes.value.some((item) => item.parentId === node.id) ||
    editor.edges.value.some((edge) => edge.sourceNodeId === node.id || edge.targetNodeId === node.id) ||
    node.resources.length > 0
  const message = associated ? '该条目和其他部分关联，确认删除？' : '确认删除？'
  if (!(await confirm(message, `删除${NODE_KIND_LABELS[node.kind]}`))) return
  await editor.deleteNode(node.id)
}

const edgeVisible = ref(false)
const edgeKind = ref<EdgeKind>('prerequisite')

function openAddEdge(kind: EdgeKind): void {
  edgeKind.value = kind
  edgeVisible.value = true
}

async function submitEdge(targetId: number): Promise<void> {
  const node = editor.selected.value
  if (!node) return
  // 前置:所选知识点是本知识点的前置(source → target = 先学 source)
  const request =
    edgeKind.value === 'prerequisite'
      ? { sourceNodeId: targetId, targetNodeId: node.id, kind: edgeKind.value }
      : { sourceNodeId: node.id, targetNodeId: targetId, kind: edgeKind.value }
  if (await editor.createEdge(request)) edgeVisible.value = false
}

async function removeEdge(edge: EdgeView): Promise<void> {
  if (!(await confirm('确认删除？', '删除关系'))) return
  await editor.deleteEdge(edge.id)
}

const attachVisible = ref(false)
const attachedKeys = computed(
  () => new Set((editor.selected.value?.resources ?? []).map((r) => resourceKey(r.itemType, r.contentId))),
)

async function attach(content: LibraryContent): Promise<void> {
  const node = editor.selected.value
  if (!node) return
  await editor.attachResource(node.id, { itemType: content.itemType, contentId: content.contentId })
}

async function detach(resource: NodeResourceView): Promise<void> {
  const node = editor.selected.value
  if (!node) return
  if (!(await confirm('确认卸载？', '卸载资源'))) return
  await editor.detachResource(node.id, resource.itemType, resource.contentId)
}

async function openResource(resource: NodeResourceView): Promise<void> {
  const node = editor.selected.value
  if (!node) return
  if (resource.itemType === 'programming_problem') {
    window.open(`/focus/admin/courses/${courseId.value}/programming-problems/${resource.contentId}/edit`, '_blank')
    return
  }
  if (resource.itemType === 'question') {
    window.open(`/focus/admin/courses/${courseId.value}/questions/${resource.contentId}/edit`, '_blank')
    return
  }
  try {
    const response = await api.courseKnowledgeGraphOpenResource(
      courseId.value,
      graphId.value,
      node.id,
      resource.itemType,
      resource.contentId,
      { management: true },
    )
    if (response.data.downloadUrl) window.open(response.data.downloadUrl, '_blank')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

interface ContextMenuItem {
  label: string
  danger?: boolean
  run: () => void
}

const contextMenu = ref({ visible: false, x: 0, y: 0 })

const contextMenuItems = computed<ContextMenuItem[]>(() => {
  const node = editor.selected.value
  if (!node) {
    return [
      { label: '添加章节', run: () => openAddChild('unit') },
      { label: '添加知识点', run: () => openAddChild('knowledge_point') },
    ]
  }
  const actions: ContextMenuItem[] = []
  if (node.kind === 'unit') {
    actions.push({ label: '添加子章节', run: () => openAddChild('unit') })
    actions.push({ label: '添加知识点', run: () => openAddChild('knowledge_point') })
    actions.push({ label: '挂载资源', run: () => (attachVisible.value = true) })
  } else if (node.kind === 'knowledge_point') {
    actions.push({ label: '添加代码示例', run: () => openAddChild('code_example') })
    actions.push({ label: '添加前置', run: () => openAddEdge('prerequisite') })
    actions.push({ label: '添加相关', run: () => openAddEdge('related') })
    actions.push({ label: '挂载资源', run: () => (attachVisible.value = true) })
  }
  actions.push({ label: '编辑', run: openEdit })
  actions.push({ label: '删除', danger: true, run: () => void removeNode() })
  return actions
})

function onCanvasContext(id: string | null, x: number, y: number): void {
  selectKey(id)
  contextMenu.value = { visible: true, x, y }
}

function closeContextMenu(): void {
  if (contextMenu.value.visible) contextMenu.value = { ...contextMenu.value, visible: false }
}

function runContextMenuItem(item: ContextMenuItem): void {
  closeContextMenu()
  item.run()
}

function onNodeOpen(id: string): void {
  selectKey(id)
  openEdit()
}

function goBack(): void {
  void router.push(`/admin/courses/${courseId.value}?tab=knowledge-graphs`)
}

onMounted(() => {
  window.addEventListener('click', closeContextMenu)
  window.addEventListener('scroll', closeContextMenu, true)
})
onBeforeUnmount(() => {
  window.removeEventListener('click', closeContextMenu)
  window.removeEventListener('scroll', closeContextMenu, true)
})

/** 同一组件实例在图谱之间切换(浏览器前进 / 后退)也要重载,不能只在挂载时加载一次 */
watch(
  [courseId, graphId],
  async () => {
    editor.select(null)
    if (!Number.isSafeInteger(courseId.value) || !Number.isSafeInteger(graphId.value)) {
      editor.loading.value = false
      editor.loadError.value = '当前图谱地址无效'
      return
    }
    await editor.load()
    // 学情报告等入口带 ?node=<id> 直达节点
    const focusId = Number(route.query.node)
    if (Number.isSafeInteger(focusId) && editor.nodes.value.some((node) => node.id === focusId)) {
      await selectAndReveal(focusId)
    }
  },
  { immediate: true },
)

</script>

<template>
  <div class="workspace">
    <AsyncState
      :loading="editor.loading.value"
      :error="editor.loadError.value"
      :empty="!editor.graph.value"
      @retry="editor.load"
    >
      <template v-if="editor.graph.value">
        <header class="workspace-toolbar">
          <el-button @click="goBack">返回课程</el-button>
          <h3 class="workspace-title">{{ editor.graph.value.name }}</h3>
          <el-input
            v-model="search"
            placeholder="搜索节点"
            clearable
            :prefix-icon="Search"
            class="workspace-search"
            @keyup.enter="searchNode"
          />
          <el-select
            v-model="categoryFilter"
            multiple
            collapse-tags
            clearable
            placeholder="全部类型"
            class="category-filter"
          >
            <el-option v-for="category in categories" :key="category" :label="category" :value="category" />
          </el-select>
        </header>

        <el-splitter class="workspace-body">
          <el-splitter-panel :min="480">
            <div class="view-host">
              <GraphMindMap
                v-if="editor.nodes.value.length"
                ref="mindMap"
                :key="graphId"
                :root-label="editor.graph.value.name"
                :items="items"
                :selected-id="selectedKey"
                :highlights="highlights"
                :visible-categories="categoryFilter.length ? categoryFilter : undefined"
                @select="selectKey"
                @context="onCanvasContext"
                @open="onNodeOpen"
              />
              <div
                v-else
                class="graph-empty"
                @contextmenu.prevent="onCanvasContext(null, $event.clientX, $event.clientY)"
              >
                <p>还没有内容</p>
              </div>
            </div>
          </el-splitter-panel>
          <el-splitter-panel
            :size="inspectorWidth"
            :min="INSPECTOR_MIN"
            :max="INSPECTOR_MAX"
            @update:size="(value: number | string) => (inspectorWidth = Number(value))"
          >
            <NodeInspector
              :graph="editor.graph.value"
              :node="editor.selected.value"
              :nodes="editor.nodes.value"
              :edges="editor.edges.value"
              :editable="true"
              :busy="editor.busy.value"
              class="inspector-host"
              @add-child="openAddChild"
              @edit="openEdit"
              @remove="removeNode"
              @add-edge="openAddEdge"
              @remove-edge="removeEdge"
              @attach="attachVisible = true"
              @detach="detach"
              @open-resource="openResource"
              @select="selectAndReveal"
            />
          </el-splitter-panel>
        </el-splitter>

        <NodeFormDialog
          v-model="formVisible"
          :kind="formKind"
          :initial="formInitial"
          :busy="editor.busy.value"
          @submit="submitForm"
        />
        <EdgeDialog
          v-model="edgeVisible"
          :kind="edgeKind"
          :source="editor.selected.value"
          :nodes="editor.nodes.value"
          :edges="editor.edges.value"
          :busy="editor.busy.value"
          @submit="submitEdge"
        />
        <ResourceAttachDialog
          v-model="attachVisible"
          :course-id="courseId"
          :busy="editor.busy.value"
          :attached="attachedKeys"
          @pick="attach"
        />

        <Teleport to="body">
          <ul
            v-if="contextMenu.visible"
            class="context-menu"
            :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
            @click.stop
          >
            <li
              v-for="item in contextMenuItems"
              :key="item.label"
              :class="{ danger: item.danger }"
              @click="runContextMenuItem(item)"
            >
              {{ item.label }}
            </li>
          </ul>
        </Teleport>
      </template>
    </AsyncState>
  </div>
</template>

<style scoped>
.workspace {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px 20px 20px;
  min-height: 100vh;
  background: #f4f6fa;
}

.workspace-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.workspace-title {
  margin: 0 auto 0 4px;
  font-size: 16px;
}

.workspace-search {
  width: 200px;
}

.category-filter {
  width: 200px;
}

.workspace-body {
  flex: 1;
  height: calc(100vh - 150px);
  min-height: 560px;
}

.view-host {
  min-width: 0;
  height: 100%;
  padding-right: 10px;
}

.view-host > * {
  height: 100%;
}

.graph-empty {
  display: grid;
  place-content: center;
  text-align: center;
  border: 1px dashed var(--el-border-color);
  border-radius: 12px;
  background: #fff;
  color: var(--el-text-color-secondary);
}

.graph-empty p {
  margin: 0;
}

.inspector-host {
  height: 100%;
  margin-left: 10px;
}

.context-menu {
  position: fixed;
  z-index: 3000;
  margin: 0;
  padding: 6px;
  list-style: none;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: var(--el-box-shadow-light);
  min-width: 132px;
}

.context-menu li {
  padding: 7px 12px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.context-menu li:hover {
  background: var(--el-fill-color-light);
}

.context-menu li.danger {
  color: var(--el-color-danger);
}
</style>
