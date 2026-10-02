<script setup lang="ts">
import { computed } from 'vue'
import type { HeadingBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

const props = defineProps<{ block: HeadingBlock; fontScale: number }>()

const style = computed(() => {
  const t = DEFAULT_THEME
  const size = (props.block.level === 1 ? t.fontSize.h1 : t.fontSize.h2) * props.fontScale
  return {
    fontSize: `${size}px`,
    lineHeight: String(t.lineHeight),
    color: t.colors.text,
    fontWeight: '700',
  }
})
</script>

<template>
  <div :style="style"><InlineText :text="block.text" /></div>
</template>
