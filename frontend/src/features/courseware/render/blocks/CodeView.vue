<script setup lang="ts">
/** 代码块:等宽渲染。内边距 14px 与 measureBlock 的 CODE_PADDING 对应 */
import type { CodeBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

defineProps<{ block: CodeBlock; fontScale: number }>()

const t = DEFAULT_THEME
</script>

<template>
  <div>
    <pre
      :style="{
        margin: '0',
        padding: `${14 * fontScale}px`,
        background: t.colors.codeBackground,
        color: t.colors.codeText,
        borderRadius: '8px',
        fontFamily: t.fontFamily.code,
        fontSize: `${t.fontSize.code * fontScale}px`,
        lineHeight: String(t.codeLineHeight),
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
        overflow: 'hidden',
      }"
      >{{ block.code }}</pre>
    <div
      v-if="block.caption"
      :style="{
        marginTop: `${t.spacing.xs * fontScale}px`,
        fontSize: `${t.fontSize.small * fontScale}px`,
        color: t.colors.muted,
      }"
    >
      <InlineText :text="block.caption" />
    </div>
  </div>
</template>
