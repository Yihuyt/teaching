<script setup lang="ts">
/**
 * 选择题块。判分在服务端完成:
 * - 播放(interactive)模式:选择 → emit submit(chosen) → 父层调判分接口 →
 *   verdict prop 回传后据其展示对错着色。播放视图的 block.answer 是空的(服务端剥除)。
 * - 编辑/静态预览:只展示题干与选项。
 * 选项缩进 2.2em 与布局测量对应;explanation 由父层(讲解卡)展示。
 */
import { ref, watch } from 'vue'
import type { QuizChoiceBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import type { QuizVerdict } from '@/features/courseware/render/types'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

const props = defineProps<{
  block: QuizChoiceBlock
  fontScale: number
  interactive?: boolean
  /** 服务端判分结果(父层回传);非空即视为已作答 */
  verdict?: QuizVerdict | null
  /** 重访已答页时恢复学生当时的选择(配合 verdict 还原红绿标注) */
  initialChosen?: string[] | null
}>()

const emit = defineEmits<{ submit: [chosen: string[]] }>()

const t = DEFAULT_THEME
const chosen = ref<Set<string>>(new Set(props.initialChosen ?? []))
const submitting = ref(false)

// 判分请求失败时父层会把 verdict 清回 null,恢复可提交状态
watch(
  () => props.verdict,
  (v) => {
    if (!v) submitting.value = false
  },
)

function toggle(label: string): void {
  if (!props.interactive || props.verdict || submitting.value) return
  if (props.block.multiple) {
    if (chosen.value.has(label)) chosen.value.delete(label)
    else chosen.value.add(label)
    chosen.value = new Set(chosen.value)
  } else {
    chosen.value = new Set([label])
  }
}

function submit(): void {
  if (chosen.value.size === 0 || props.verdict || submitting.value) return
  submitting.value = true
  emit('submit', [...chosen.value])
}

function optionStyle(label: string): Record<string, string> {
  const base: Record<string, string> = {
    display: 'flex',
    padding: `${8 * props.fontScale}px ${12 * props.fontScale}px`,
    borderRadius: '8px',
    border: `1.5px solid ${t.colors.tableBorder}`,
    cursor: props.interactive && !props.verdict ? 'pointer' : 'default',
  }
  if (chosen.value.has(label)) {
    base['borderColor'] = t.colors.primary
    base['background'] = t.colors.callout.info.background
  }
  if (props.verdict) {
    if (props.verdict.answer.includes(label)) {
      base['borderColor'] = t.colors.verdict.correct
      base['background'] = t.colors.verdict.correctBackground
    } else if (chosen.value.has(label)) {
      base['borderColor'] = t.colors.verdict.wrong
      base['background'] = t.colors.verdict.wrongBackground
    }
  }
  return base
}
</script>

<template>
  <div
    :style="{
      fontSize: `${t.fontSize.body * fontScale}px`,
      lineHeight: String(t.lineHeight),
      color: t.colors.text,
    }"
  >
    <div :style="{ fontWeight: '600' }"><InlineText :text="block.stem" /></div>
    <div
      :style="{
        marginTop: `${t.spacing.sm * fontScale}px`,
        display: 'grid',
        gap: `${t.spacing.xs * fontScale}px`,
      }"
    >
      <div
        v-for="option in block.options"
        :key="option.label"
        :style="optionStyle(option.label)"
        @click="toggle(option.label)"
      >
        <span :style="{ width: '2.2em', flex: 'none', fontWeight: '600', color: t.colors.primary }"
          >{{ option.label }}.</span
        >
        <span :style="{ flex: '1' }"><InlineText :text="option.text" /></span>
      </div>
    </div>
    <div v-if="interactive && !verdict" :style="{ marginTop: `${t.spacing.sm * fontScale}px` }">
      <el-button type="primary" :disabled="chosen.size === 0" :loading="submitting" @click="submit"
        >提交答案</el-button
      >
    </div>
  </div>
</template>
