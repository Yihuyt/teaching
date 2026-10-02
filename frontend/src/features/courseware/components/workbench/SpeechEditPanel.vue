<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Action, Block, EditOp, Scene } from '@/features/courseware/dsl'
import { BLOCK_TYPE_LABELS, isBlocklessScene, toEditSpeech } from '@/features/courseware/dsl'

const props = defineProps<{ scene: Scene; busy: boolean }>()
const emit = defineEmits<{ ops: [ops: EditOp[]] }>()

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

const draft = ref<Scene>(clone(props.scene))
watch(
  () => props.scene,
  (scene) => {
    draft.value = clone(scene)
  },
)

/** 可作为动作目标的选项:内容块与要点的每一条(交互页、视频页没有块,只能停顿) */
const targetOptions = computed(() => {
  const options: { value: string; label: string }[] = []
  if (isBlocklessScene(draft.value.type)) return options
  const add = (block: Block, label: string): void => {
    options.push({ value: block.id, label })
    if (block.type === 'bullets') {
      block.items.forEach((_, i) => options.push({ value: `${block.id}#${i + 1}`, label: `${label} 第 ${i + 1} 条` }))
    }
  }
  draft.value.blocks.forEach((block, i) => {
    const label = `第 ${i + 1} 块 ${BLOCK_TYPE_LABELS[block.type]}`
    add(block, label)
    if (block.type === 'columns') {
      block.children.forEach((column, ci) =>
        column.forEach((child) => add(child, `${label} 第 ${ci + 1} 栏 ${BLOCK_TYPE_LABELS[child.type]}`)),
      )
    }
  })
  return options
})

function commit(): void {
  emit('ops', [{ op: 'set_speech', sceneId: draft.value.id, speech: toEditSpeech(draft.value.speech) }])
}

function addSegment(): void {
  draft.value.speech.push({ text: '(新讲稿段)', actions: [] })
  commit()
}

function removeSegment(index: number): void {
  draft.value.speech.splice(index, 1)
  commit()
}

function addAction(segIndex: number): void {
  const first = targetOptions.value[0]
  const action: Action = first ? { type: 'highlight', target: first.value } : { type: 'pause', ms: 500 }
  draft.value.speech[segIndex]?.actions.push(action)
  commit()
}

function removeAction(segIndex: number, actionIndex: number): void {
  draft.value.speech[segIndex]?.actions.splice(actionIndex, 1)
  commit()
}

function changeActionType(segIndex: number, actionIndex: number, type: string): void {
  const segment = draft.value.speech[segIndex]
  if (!segment) return
  const first = targetOptions.value[0]?.value ?? ''
  const next: Action =
    (type === 'highlight' || type === 'reveal') && first
      ? { type, target: first }
      : { type: 'pause', ms: 500 }
  segment.actions.splice(actionIndex, 1, next)
  commit()
}
</script>

<template>
  <div class="panel-body">
    <div v-for="(segment, si) in draft.speech" :key="si" class="segment-card">
      <div class="segment-header">
        <span class="segment-index">第 {{ si + 1 }} 段</span>
        <el-tag v-if="segment.audioPath" size="small" type="success">已合成</el-tag>
        <span class="spacer" />
        <el-button text size="small" type="danger" :disabled="busy" @click="removeSegment(si)">删</el-button>
      </div>
      <el-input
        v-model="segment.text"
        type="textarea"
        :rows="2"
        placeholder="老师说的话(口语化)"
        :disabled="busy"
        @change="commit"
      />
      <div v-for="(action, ai) in segment.actions" :key="ai" class="action-row">
        <el-select
          size="small"
          style="width: 96px"
          :model-value="action.type"
          :disabled="busy"
          @update:model-value="(v: string) => changeActionType(si, ai, v)"
        >
          <el-option v-if="targetOptions.length" label="高亮" value="highlight" />
          <el-option v-if="targetOptions.length" label="揭示" value="reveal" />
          <el-option label="停顿" value="pause" />
        </el-select>
        <el-select
          v-if="action.type === 'highlight' || action.type === 'reveal'"
          v-model="action.target"
          size="small"
          filterable
          :disabled="busy"
          @change="commit"
        >
          <el-option v-for="opt in targetOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-input-number
          v-else-if="action.type === 'pause'"
          v-model="action.ms"
          size="small"
          :min="200"
          :max="5000"
          :step="100"
          :disabled="busy"
          @change="commit"
        />
        <el-button text size="small" type="danger" :disabled="busy" @click="removeAction(si, ai)">−</el-button>
      </div>
      <el-button text size="small" :disabled="busy" @click="addAction(si)">+ 加动作</el-button>
    </div>
    <el-button class="add-segment" :disabled="busy" @click="addSegment">+ 添加讲稿段</el-button>
  </div>
</template>

<style scoped>
.panel-body {
  padding: 12px 0 24px;
}

.segment-card {
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  padding: 10px;
  margin-bottom: 10px;
}

.segment-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.segment-index {
  font-size: 13px;
  font-weight: 600;
}

.spacer {
  flex: 1;
}

.action-row {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-top: 6px;
}

.add-segment {
  margin-top: 8px;
}
</style>
