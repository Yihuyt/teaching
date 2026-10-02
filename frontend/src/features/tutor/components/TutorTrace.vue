<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Loading, CircleCheck, Warning, Search } from '@element-plus/icons-vue'

import type { TutorTraceRound } from '@/features/tutor/tutorStream'

const props = defineProps<{ rounds: TutorTraceRound[]; running: boolean }>()

const open = ref(props.running)
watch(
  () => props.running,
  (running) => {
    open.value = running
  },
)

const toolLabels: Readonly<Record<string, string>> = {
  rag: '检索知识库',
}

const toolCount = computed(() => props.rounds.reduce((sum, round) => sum + round.tools.length, 0))
const hasSubstance = computed(() =>
  props.rounds.some((round) => round.thinking || round.narration || round.tools.length),
)

function argsText(args: string): string {
  try {
    const parsed = JSON.parse(args) as Record<string, unknown>
    return Object.values(parsed)
      .map((value) => String(value))
      .join(' · ')
  } catch {
    return args
  }
}
</script>

<template>
  <div v-if="hasSubstance || running" class="trace">
    <button type="button" class="trace-head" @click="open = !open">
      <el-icon v-if="running" class="spin"><Loading /></el-icon>
      <el-icon v-else><CircleCheck /></el-icon>
      <span>
        {{ running ? '正在思考与检索…' : `执行过程(${rounds.length} 轮,${toolCount} 次工具调用)` }}
      </span>
      <span class="trace-toggle">{{ open ? '收起' : '展开' }}</span>
    </button>
    <div v-if="open" class="trace-body">
      <div v-for="round in rounds" :key="round.callId" class="trace-round">
        <p v-if="round.thinking" class="trace-thinking">{{ round.thinking }}</p>
        <p v-if="round.narration" class="trace-narration">{{ round.narration }}</p>
        <div v-for="tool in round.tools" :key="tool.toolCallId" class="trace-tool">
          <el-icon v-if="tool.running" class="spin"><Loading /></el-icon>
          <el-icon v-else-if="tool.error" class="warn"><Warning /></el-icon>
          <el-icon v-else><Search /></el-icon>
          <span class="trace-tool-name">{{ toolLabels[tool.name] ?? tool.name }}</span>
          <span class="trace-tool-args">{{ argsText(tool.args) }}</span>
          <span v-if="tool.summary" class="trace-tool-summary">→ {{ tool.summary }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trace {
  margin-bottom: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  font-size: 12px;
}

.trace-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 10px;
  border: 0;
  background: transparent;
  color: var(--el-text-color-secondary);
  cursor: pointer;
  text-align: left;
}

.trace-toggle {
  margin-left: auto;
  color: var(--el-color-primary);
}

.trace-body {
  padding: 0 10px 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.trace-round {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.trace-thinking {
  margin: 0;
  color: var(--el-text-color-placeholder);
  white-space: pre-wrap;
}

.trace-narration {
  margin: 0;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
}

.trace-tool {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  color: var(--el-text-color-regular);
}

.trace-tool-name {
  font-weight: 600;
}

.trace-tool-args,
.trace-tool-summary {
  color: var(--el-text-color-secondary);
}

.spin {
  animation: spin 1s linear infinite;
}

.warn {
  color: var(--el-color-warning);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
