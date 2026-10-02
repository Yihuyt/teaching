<script setup lang="ts">
/** 块分发器:按 block.type 挑对应视图。columns 容器不在此渲染(子块各有帧)。 */
import type { Block } from '@/features/courseware/dsl'
import HeadingView from '@/features/courseware/render/blocks/HeadingView.vue'
import ParagraphView from '@/features/courseware/render/blocks/ParagraphView.vue'
import BulletsView from '@/features/courseware/render/blocks/BulletsView.vue'
import FormulaView from '@/features/courseware/render/blocks/FormulaView.vue'
import CodeView from '@/features/courseware/render/blocks/CodeView.vue'
import TableView from '@/features/courseware/render/blocks/TableView.vue'
import ChartView from '@/features/courseware/render/blocks/ChartView.vue'
import EmphasisView from '@/features/courseware/render/blocks/EmphasisView.vue'
import ImageView from '@/features/courseware/render/blocks/ImageView.vue'
import CalloutView from '@/features/courseware/render/blocks/CalloutView.vue'
import QuizChoiceView from '@/features/courseware/render/blocks/QuizChoiceView.vue'

const props = defineProps<{
  block: Block
  fontScale: number
  assetUrls?: Readonly<Record<string, string>> | undefined
  quizInteractive?: boolean
  quizVerdict?: import('@/features/courseware/render/types').QuizVerdict | null
  quizChosen?: string[] | null
  /** 当前高亮目标(`blk-x` 或 `blk-x#n`),bullets 用它算条目级高亮 */
  highlightTarget?: string | null
}>()

const emit = defineEmits<{ quizSubmit: [blockId: string, chosen: string[]] }>()

import { computed } from 'vue'

const highlightItem = computed<number | null>(() => {
  const target = props.highlightTarget
  if (!target) return null
  const [blockId, item] = target.split('#')
  if (blockId !== props.block.id || item === undefined) return null
  const n = Number(item)
  return Number.isInteger(n) && n >= 1 ? n : null
})
</script>

<template>
  <HeadingView v-if="block.type === 'heading'" :block="block" :font-scale="fontScale" />
  <ParagraphView v-else-if="block.type === 'paragraph'" :block="block" :font-scale="fontScale" />
  <BulletsView
    v-else-if="block.type === 'bullets'"
    :block="block"
    :font-scale="fontScale"
    :highlight-item="highlightItem"
  />
  <FormulaView v-else-if="block.type === 'formula'" :block="block" :font-scale="fontScale" />
  <CodeView v-else-if="block.type === 'code'" :block="block" :font-scale="fontScale" />
  <TableView v-else-if="block.type === 'table'" :block="block" :font-scale="fontScale" />
  <ChartView v-else-if="block.type === 'chart'" :block="block" :font-scale="fontScale" />
  <EmphasisView v-else-if="block.type === 'emphasis'" :block="block" :font-scale="fontScale" />
  <ImageView
    v-else-if="block.type === 'image'"
    :block="block"
    :font-scale="fontScale"
    :url="assetUrls?.[block.src]"
  />
  <CalloutView v-else-if="block.type === 'callout'" :block="block" :font-scale="fontScale" />
  <QuizChoiceView
    v-else-if="block.type === 'quiz_choice'"
    :block="block"
    :font-scale="fontScale"
    :interactive="quizInteractive ?? false"
    :verdict="quizVerdict ?? null"
    :initial-chosen="quizChosen ?? null"
    @submit="(chosen) => emit('quizSubmit', block.id, chosen)"
  />
</template>
