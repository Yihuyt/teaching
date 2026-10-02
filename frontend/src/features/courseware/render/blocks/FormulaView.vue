<script setup lang="ts">
import { computed } from 'vue'
import katex from 'katex'
import type { FormulaBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

const props = defineProps<{ block: FormulaBlock; fontScale: number }>()

const t = DEFAULT_THEME

const html = computed(() =>
  katex.renderToString(props.block.latex, {
    displayMode: true,
    throwOnError: false,
    output: 'html',
  }),
)
</script>

<template>
  <div :style="{ textAlign: 'center' }">
    <div
      :style="{ fontSize: `${t.fontSize.body * fontScale * 1.15}px`, color: t.colors.text }"
      v-html="html"
    />
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
