<script setup lang="ts">
import { computed } from 'vue'
import type { ParagraphBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

const props = defineProps<{ block: ParagraphBlock; fontScale: number }>()

const style = computed(() => ({
  fontSize: `${DEFAULT_THEME.fontSize.body * props.fontScale}px`,
  lineHeight: String(DEFAULT_THEME.lineHeight),
  color: DEFAULT_THEME.colors.text,
}))
</script>

<template>
  <div :style="style"><InlineText :text="block.text" /></div>
</template>
