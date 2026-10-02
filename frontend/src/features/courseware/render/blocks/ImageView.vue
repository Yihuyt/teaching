<script setup lang="ts">
import type { ImageBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

defineProps<{ block: ImageBlock; fontScale: number; url?: string | undefined }>()

const t = DEFAULT_THEME
</script>

<template>
  <div :style="{ height: '100%', display: 'flex', flexDirection: 'column' }">
    <div
      :style="{
        flex: '1',
        minHeight: '0',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }"
    >
      <img
        v-if="url"
        :src="url"
        :alt="block.caption ?? ''"
        :style="{ maxWidth: '100%', maxHeight: '100%', width: 'auto', height: 'auto', objectFit: 'contain' }"
      />
      <div
        v-else
        class="image-placeholder"
        :style="{
          fontSize: `${t.fontSize.small * fontScale}px`,
          color: t.colors.muted,
        }"
      >
        [图片:{{ block.caption || block.src }}]
      </div>
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
