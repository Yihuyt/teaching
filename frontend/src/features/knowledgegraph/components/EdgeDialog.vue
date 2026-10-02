<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import type { EdgeView, NodeView } from '@/api/generated'
import { unitPathResolver, type EdgeKind } from '@/features/knowledgegraph/knowledgeGraph'

/**
 * 建立关系:从本知识点出发选另一个知识点。前置 = 「先学它,再学本知识点」;相关 = 无向。
 * 候选排除自身与已有任何关系的知识点;成环由服务端拒绝并给出环路。
 */
const props = defineProps<{
  kind: EdgeKind
  source: NodeView | null
  nodes: NodeView[]
  edges: EdgeView[]
  busy: boolean
}>()

const emit = defineEmits<{ submit: [targetId: number] }>()
const visible = defineModel<boolean>({ required: true })

const targetId = ref<number>()

const candidates = computed(() => {
  const source = props.source
  if (!source) return []
  const connected = new Set<number>()
  for (const edge of props.edges) {
    if (edge.sourceNodeId === source.id) connected.add(edge.targetNodeId)
    if (edge.targetNodeId === source.id) connected.add(edge.sourceNodeId)
  }
  const resolvePath = unitPathResolver(props.nodes)
  return props.nodes
    .filter((node) => node.kind === 'knowledge_point' && node.id !== source.id && !connected.has(node.id))
    .map((node) => ({ id: node.id, label: node.label, path: resolvePath(node.id).join(' › ') }))
})

watch(visible, (open) => {
  if (open) targetId.value = undefined
})

function submit(): void {
  if (targetId.value === undefined) return
  emit('submit', targetId.value)
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="kind === 'prerequisite' ? '添加前置知识点' : '添加相关知识点'"
    width="520px"
    destroy-on-close
    append-to-body
  >
    <p v-if="source" class="hint">
      <template v-if="kind === 'prerequisite'">选择学「{{ source.label }}」之前应先掌握的知识点</template>
      <template v-else>选择与「{{ source.label }}」相关的知识点</template>
    </p>
    <el-select
      v-model="targetId"
      filterable
      placeholder="搜索知识点"
      class="target-select"
      :no-data-text="candidates.length ? '没有匹配的知识点' : '没有可选的知识点'"
    >
      <el-option
        v-for="candidate in candidates"
        :key="candidate.id"
        :value="candidate.id"
        :label="candidate.label"
      >
        <span>{{ candidate.label }}</span>
        <span v-if="candidate.path" class="candidate-path">{{ candidate.path }}</span>
      </el-option>
    </el-select>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="targetId === undefined" @click="submit"
        >确定</el-button
      >
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.target-select {
  width: 100%;
}

.candidate-path {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
