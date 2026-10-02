<script setup lang="ts">
import { computed } from 'vue'
import { Delete, Edit, Link, Paperclip, Plus } from '@element-plus/icons-vue'

import type { EdgeView, GraphView, NodeResourceView, NodeView } from '@/api/generated'
import {
  EDGE_KIND_LABELS,
  NODE_KIND_LABELS,
  RESOURCE_TYPE_LABELS,
  categoryColor,
  categoryOf,
  otherEnd,
  relationsOf,
  unitPath,
  type EdgeKind,
  type NodeKind,
  NODE_TEXT_LABELS,
  resourceKey,
} from '@/features/knowledgegraph/knowledgeGraph'

const props = defineProps<{
  graph: GraphView
  node: NodeView | null
  nodes: NodeView[]
  edges: EdgeView[]
  editable: boolean
  busy: boolean
}>()

const emit = defineEmits<{
  'add-child': [kind: NodeKind]
  edit: []
  remove: []
  'add-edge': [kind: EdgeKind]
  'remove-edge': [edge: EdgeView]
  attach: []
  detach: [resource: NodeResourceView]
  'open-resource': [resource: NodeResourceView]
  select: [id: number]
}>()

const byId = computed(() => new Map(props.nodes.map((node) => [node.id, node])))
const nodeText = computed(() =>
  props.node ? (props.node.summary ?? props.node.definition ?? props.node.explanation) : null,
)
const path = computed(() => (props.node ? unitPath(props.nodes, props.node.id) : []))
const relations = computed(() => (props.node ? relationsOf(props.node.id, props.edges) : null))

interface RelationGroup {
  key: 'prerequisites' | 'successors' | 'related'
  label: string
  edges: EdgeView[]
}

const relationGroups = computed<RelationGroup[]>(() => {
  if (!relations.value) return []
  return [
    { key: 'prerequisites', label: '前置知识点', edges: relations.value.prerequisites },
    { key: 'successors', label: '后续知识点', edges: relations.value.successors },
    { key: 'related', label: '相关知识点', edges: relations.value.related },
  ].filter((group) => group.edges.length > 0) as RelationGroup[]
})

function labelOf(id: number): string {
  return byId.value.get(id)?.label ?? '（已删除）'
}
</script>

<template>
  <aside class="inspector">
    <template v-if="!node">
      <header class="inspector-head">
        <el-tag size="small" effect="dark" color="#1f6feb" class="kind-tag">图谱</el-tag>
        <h4 class="inspector-title">{{ graph.name }}</h4>
      </header>
      <p class="inspector-hint">点击导图中的节点查看详情{{ editable ? '与操作' : '' }}</p>
      <div v-if="editable" class="action-row">
        <el-button :icon="Plus" :disabled="busy" @click="emit('add-child', 'unit')">添加章节</el-button>
        <el-button :icon="Plus" :disabled="busy" @click="emit('add-child', 'knowledge_point')"
          >添加知识点</el-button
        >
      </div>
    </template>

    <template v-else>
      <header class="inspector-head">
        <el-tag size="small" effect="dark" :color="categoryColor(categoryOf(node))" class="kind-tag">
          {{ NODE_KIND_LABELS[node.kind] }}<template v-if="node.kpType"> · {{ node.kpType }}</template>
        </el-tag>
        <h4 class="inspector-title">{{ node.label }}</h4>
      </header>
      <p v-if="path.length" class="inspector-path">{{ path.join(' › ') }}</p>

      <div v-if="editable" class="action-row">
        <template v-if="node.kind === 'unit'">
          <el-button :icon="Plus" :disabled="busy" @click="emit('add-child', 'unit')">子章节</el-button>
          <el-button :icon="Plus" :disabled="busy" @click="emit('add-child', 'knowledge_point')">
            知识点
          </el-button>
        </template>
        <template v-else-if="node.kind === 'knowledge_point'">
          <el-button :icon="Plus" :disabled="busy" @click="emit('add-child', 'code_example')">
            代码示例
          </el-button>
          <el-button :icon="Link" :disabled="busy" @click="emit('add-edge', 'prerequisite')">
            添加前置
          </el-button>
          <el-button :icon="Link" :disabled="busy" @click="emit('add-edge', 'related')">添加相关</el-button>
        </template>
        <el-button :icon="Edit" :disabled="busy" @click="emit('edit')">编辑</el-button>
        <el-button type="danger" plain :icon="Delete" :disabled="busy" @click="emit('remove')">
          删除
        </el-button>
      </div>

      <section v-if="nodeText" class="inspector-section">
        <h5>{{ NODE_TEXT_LABELS[node.kind] }}</h5>
        <p class="inspector-text">{{ nodeText }}</p>
      </section>
      <section v-if="node.aliases.length" class="inspector-section">
        <h5>别名</h5>
        <div class="tag-row">
          <el-tag v-for="alias in node.aliases" :key="alias" size="small" effect="plain">{{ alias }}</el-tag>
        </div>
      </section>
      <section v-if="node.code" class="inspector-section">
        <h5>
          代码<span v-if="node.language" class="code-language">{{ node.language }}</span>
        </h5>
        <pre class="code-block">{{ node.code }}</pre>
      </section>
      <section v-if="node.sourceSectionTitle || node.quote" class="inspector-section">
        <h5>出处</h5>
        <p v-if="node.sourceSectionTitle" class="inspector-text">{{ node.sourceSectionTitle }}</p>
        <blockquote v-if="node.quote" class="quote">{{ node.quote }}</blockquote>
      </section>

      <section v-for="group in relationGroups" :key="group.key" class="inspector-section">
        <h5>{{ group.label }}</h5>
        <ul class="relation-list">
          <li v-for="edge in group.edges" :key="edge.id">
            <button type="button" class="relation-link" @click="emit('select', otherEnd(edge, node.id))">
              {{ labelOf(otherEnd(edge, node.id)) }}
            </button>
            <span v-if="edge.evidence" class="relation-evidence" :title="edge.evidence">{{
              edge.evidence
            }}</span>
            <el-button
              v-if="editable"
              link
              type="danger"
              :disabled="busy"
              :aria-label="`删除${EDGE_KIND_LABELS[edge.kind]}关系`"
              @click="emit('remove-edge', edge)"
            >
              删除
            </el-button>
          </li>
        </ul>
      </section>

      <section v-if="node.kind !== 'code_example'" class="inspector-section">
        <h5>
          挂载资源
          <el-button
            v-if="editable"
            link
            type="primary"
            :icon="Paperclip"
            :disabled="busy"
            @click="emit('attach')"
          >
            挂载
          </el-button>
        </h5>
        <p v-if="node.resources.length === 0" class="inspector-hint">暂无挂载</p>
        <ul v-else class="resource-list">
          <li v-for="resource in node.resources" :key="resourceKey(resource.itemType, resource.contentId)">
            <el-tag size="small" effect="plain">{{ RESOURCE_TYPE_LABELS[resource.itemType] }}</el-tag>
            <button type="button" class="relation-link" @click="emit('open-resource', resource)">
              {{ resource.title }}
            </button>
            <el-button
              v-if="editable"
              link
              type="danger"
              :disabled="busy"
              :aria-label="`卸载 ${resource.title}`"
              @click="emit('detach', resource)"
            >
              卸载
            </el-button>
          </li>
        </ul>
      </section>
    </template>
  </aside>
</template>

<style scoped>
.inspector {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: #fff;
}

.inspector-head {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kind-tag {
  align-self: flex-start;
  border: none;
  color: #fff;
}

.inspector-title {
  margin: 0;
  font-size: 18px;
  word-break: break-word;
}

.inspector-path,
.inspector-hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.inspector-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.action-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.action-row .el-button + .el-button {
  margin-left: 0;
}

.inspector-section h5 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 6px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.code-language {
  font-weight: normal;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.code-block {
  margin: 0;
  padding: 10px;
  max-height: 260px;
  overflow: auto;
  border-radius: 8px;
  background: #f6f8fa;
  font-size: 13px;
  line-height: 1.5;
}

.quote {
  margin: 6px 0 0;
  padding: 6px 10px;
  border-left: 3px solid var(--el-border-color);
  color: var(--el-text-color-regular);
  font-size: 13px;
  white-space: pre-wrap;
}

.relation-list,
.resource-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.relation-list li,
.resource-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.relation-link {
  padding: 0;
  border: none;
  background: none;
  color: var(--el-color-primary);
  cursor: pointer;
  font-size: 14px;
  text-align: left;
}

.relation-evidence {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
