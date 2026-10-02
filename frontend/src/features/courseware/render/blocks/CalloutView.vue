<script setup lang="ts">
/** 强调框:内边距 16px 与 measureBlock 的 CALLOUT_PADDING 对应 */
import { computed } from 'vue'
import type { CalloutBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

const props = defineProps<{ block: CalloutBlock; fontScale: number }>()

const t = DEFAULT_THEME

const VARIANT_LABEL: Record<CalloutBlock['variant'], string> = {
  info: '说明',
  tip: '提示',
  warning: '注意',
  conclusion: '结论',
}

const palette = computed(() => t.colors.callout[props.block.variant])
</script>

<template>
  <div
    :style="{
      padding: `${16 * fontScale}px`,
      borderLeft: `4px solid ${palette.border}`,
      background: palette.background,
      borderRadius: '0 8px 8px 0',
    }"
  >
    <div
      :style="{
        fontSize: `${t.fontSize.body * fontScale}px`,
        lineHeight: String(t.lineHeight),
        fontWeight: '600',
        color: palette.border,
        marginBottom: `${t.spacing.xs * fontScale}px`,
      }"
    >
      <InlineText :text="block.title ?? VARIANT_LABEL[block.variant]" />
    </div>
    <div
      :style="{
        fontSize: `${t.fontSize.small * fontScale}px`,
        lineHeight: String(t.lineHeight),
        color: t.colors.text,
      }"
    >
      <InlineText :text="block.text" />
    </div>
  </div>
</template>
