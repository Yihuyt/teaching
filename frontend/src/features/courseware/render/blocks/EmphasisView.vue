<script setup lang="ts">
import type { EmphasisBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

defineProps<{ block: EmphasisBlock; fontScale: number }>()

const t = DEFAULT_THEME
</script>

<template>
  <div :style="{ height: '100%', display: 'flex', flexDirection: 'column' }">
    <div
      :style="{
        flex: '1',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: `${t.fontSize.h1 * fontScale}px`,
        fontWeight: '700',
        color: t.colors.primary,
        textAlign: 'center',
        lineHeight: String(t.lineHeight),
      }"
    >
      <InlineText :text="block.text" />
    </div>
    <div
      v-if="block.caption"
      :style="{
        marginTop: `${t.spacing.xs * fontScale}px`,
        fontSize: `${t.fontSize.small * fontScale}px`,
        color: t.colors.muted,
        textAlign: 'center',
        flex: 'none',
      }"
    >
      <InlineText :text="block.caption" />
    </div>
  </div>
</template>
