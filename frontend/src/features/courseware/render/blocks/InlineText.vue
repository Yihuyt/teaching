<script setup lang="ts">
import { computed } from 'vue'
import katex from 'katex'
import { parseInline } from '@/features/courseware/inline'

const props = defineProps<{ text: string }>()

const segments = computed(() => parseInline(props.text))

function renderLatex(latex: string): string {
  return katex.renderToString(latex, { throwOnError: false, output: 'html' })
}
</script>

<template>
  <span>
    <template v-for="(seg, i) in segments" :key="i">
      <strong v-if="seg.kind === 'bold'">{{ seg.text }}</strong>
      <span v-else-if="seg.kind === 'latex'" v-html="renderLatex(seg.latex)" />
      <template v-else>{{ seg.text }}</template>
    </template>
  </span>
</template>
