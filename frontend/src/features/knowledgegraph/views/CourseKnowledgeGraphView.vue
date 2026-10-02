<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'
import type { GraphSnapshot, NodeResourceView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import { INSPECTOR_MAX, INSPECTOR_MIN, useInspectorWidth } from '@/features/knowledgegraph/useInspectorWidth'
import GraphMindMap from '@/features/knowledgegraph/components/GraphMindMap.vue'
import NodeInspector from '@/features/knowledgegraph/components/NodeInspector.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import { KP_TYPES, otherEnd, relationsOf, snapshotItems } from '@/features/knowledgegraph/knowledgeGraph'
import { openInNewTab } from '@/shared/openUrl'

const route = useRoute()
const router = useRouter()
const courseId = computed(() => Number(route.params.courseId))
const graphId = computed(() => Number(route.params.id))
const loading = ref(true)
const loadError = ref('')
const snapshot = ref<GraphSnapshot>()
const selectedId = ref<number | null>(null)
const search = ref('')
const categoryFilter = ref<string[]>([])
const mindMap = ref<InstanceType<typeof GraphMindMap>>()
const inspectorWidth = useInspectorWidth()

const categories = ['章节', ...KP_TYPES, '代码示例']
const nodes = computed(() => snapshot.value?.nodes ?? [])
const edges = computed(() => snapshot.value?.edges ?? [])
const items = computed(() => snapshotItems(nodes.value))
const selected = computed(() => nodes.value.find((node) => node.id === selectedId.value) ?? null)
const selectedKey = computed(() => (selectedId.value === null ? null : String(selectedId.value)))

const highlights = computed(() => {
  const node = selected.value
  if (!node || node.kind !== 'knowledge_point') return {}
  const relations = relationsOf(node.id, edges.value)
  const marks: Record<string, 'prerequisite' | 'successor' | 'related'> = {}
  relations.prerequisites.forEach((edge) => (marks[String(otherEnd(edge, node.id))] = 'prerequisite'))
  relations.successors.forEach((edge) => (marks[String(otherEnd(edge, node.id))] = 'successor'))
  relations.related.forEach((edge) => (marks[String(otherEnd(edge, node.id))] = 'related'))
  return marks
})

async function load(): Promise<void> {
  if (
    !Number.isSafeInteger(courseId.value) ||
    courseId.value <= 0 ||
    !Number.isSafeInteger(graphId.value) ||
    graphId.value <= 0
  ) {
    loading.value = false
    loadError.value = '当前课程或知识图谱地址无效'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    snapshot.value = (await api.courseKnowledgeGraphSnapshot(courseId.value, graphId.value)).data
    // 来源卡 / 学情报告跳转 ?node=<id>:直接选中并展开到该节点
    const focusId = Number(route.query.node)
    if (Number.isSafeInteger(focusId) && nodes.value.some((node) => node.id === focusId)) {
      await selectAndReveal(focusId)
    }
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

async function selectAndReveal(id: number): Promise<void> {
  selectedId.value = id
  await nextTick()
  mindMap.value?.reveal(String(id))
}

function selectKey(key: string | null): void {
  selectedId.value = key === null ? null : Number(key)
}

async function openResource(resource: NodeResourceView): Promise<void> {
  const node = selected.value
  if (!node) return
  try {
    const open = async () =>
      (
        await api.courseKnowledgeGraphOpenResource(
          courseId.value,
          graphId.value,
          node.id,
          resource.itemType,
          resource.contentId,
        )
      ).data
    if (resource.itemType === 'material') {
      await openInNewTab(async () => (await open()).downloadUrl ?? '')
      return
    }
    await open()
    void router.push(
      resource.itemType === 'programming_problem'
        ? `/courses/${courseId.value}/problems/${resource.contentId}`
        : `/courses/${courseId.value}/questions/${resource.contentId}`,
    )
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

function searchNode(): void {
  const keyword = search.value.trim()
  if (!keyword) return
  const hit = nodes.value.find((node) => node.label.includes(keyword))
  if (!hit) {
    ElMessage.info('没有匹配的知识点')
    return
  }
  void selectAndReveal(hit.id)
}

// 图谱之间跳转只换参数不重建组件:参数变了要重新装载
watch([courseId, graphId], load, { immediate: true })
</script>

<template>
  <div class="page page--full">
    <AsyncState :loading="loading" :error="loadError" :empty="!snapshot" @retry="load">
      <template v-if="snapshot">
        <PageHeader :title="snapshot.graph.name">
          <template #actions>
            <el-input
              v-model="search"
              placeholder="搜索知识点"
              clearable
              :prefix-icon="Search"
              class="graph-search"
              @keyup.enter="searchNode"
            />
            <el-button @click="searchNode">定位</el-button>
            <el-select
              v-model="categoryFilter"
              multiple
              collapse-tags
              clearable
              placeholder="全部类型"
              class="graph-filter"
            >
              <el-option v-for="category in categories" :key="category" :label="category" :value="category" />
            </el-select>
            <el-button @click="$router.push(`/courses/${courseId}`)">返回课程</el-button>
          </template>
        </PageHeader>
        <section class="panel graph-panel">
          <el-splitter v-if="nodes.length" class="graph-layout">
            <el-splitter-panel :min="480">
              <div class="graph-host">
                <GraphMindMap
                  ref="mindMap"
                  :key="graphId"
                  :root-label="snapshot.graph.name"
                  :items="items"
                  :selected-id="selectedKey"
                  :highlights="highlights"
                  :visible-categories="categoryFilter.length ? categoryFilter : undefined"
                  @select="selectKey"
                  @context="selectKey"
                  @open="(id: string) => selectKey(id)"
                />
              </div>
            </el-splitter-panel>
            <el-splitter-panel
              :size="inspectorWidth"
              :min="INSPECTOR_MIN"
              :max="INSPECTOR_MAX"
              @update:size="(value: number | string) => (inspectorWidth = Number(value))"
            >
              <NodeInspector
                :graph="snapshot.graph"
                :node="selected"
                :nodes="nodes"
                :edges="edges"
                :editable="false"
                :busy="false"
                class="graph-detail"
                @open-resource="openResource"
                @select="selectAndReveal"
              />
            </el-splitter-panel>
          </el-splitter>
          <p v-else class="graph-empty">暂无知识节点</p>
        </section>
      </template>
    </AsyncState>
  </div>
</template>

<style scoped>
.graph-panel {
  padding: 20px;
}

.graph-search {
  width: 180px;
}

.graph-filter {
  width: 180px;
}

.graph-layout {
  height: calc(100vh - 260px);
  min-height: 620px;
}

.graph-host {
  min-width: 0;
  height: 100%;
  padding-right: 10px;
}

.graph-host > * {
  height: 100%;
}

.graph-detail {
  height: 100%;
  margin-left: 10px;
}

.graph-empty {
  display: grid;
  min-height: 104px;
  margin: 0;
  place-items: center;
  color: var(--text-muted);
}
</style>
