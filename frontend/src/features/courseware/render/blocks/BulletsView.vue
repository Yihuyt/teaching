<script setup lang="ts">
/**
 * 要点列表。缩进/间距与 layout 的 measureBlock 保持一致:
 * 一级缩进 1.4em、二级 2.8em、条目间距 spacing.xs。
 * 每个条目带 data-item-index,供播放器按 `blk-x#n` 定位高亮。
 */
import { computed } from 'vue'
import type { BulletsBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

const props = defineProps<{
  block: BulletsBlock
  fontScale: number
  /** 高亮的条目序号(1 起),来自 `blk-x#n` 目标 */
  highlightItem?: number | null
}>()

const t = DEFAULT_THEME
const bodyPx = computed(() => t.fontSize.body * props.fontScale)
const smallPx = computed(() => t.fontSize.small * props.fontScale)
const itemGap = computed(() => t.spacing.xs * props.fontScale)
</script>

<template>
  <div
    :style="{
      fontSize: `${bodyPx}px`,
      lineHeight: String(t.lineHeight),
      color: t.colors.text,
    }"
  >
    <div
      v-for="(item, i) in block.items"
      :key="i"
      :data-item-index="i + 1"
      class="bullet-item"
      :style="{
        marginTop: i > 0 ? `${itemGap}px` : '0',
        background: highlightItem === i + 1 ? t.colors.highlight : 'transparent',
        borderRadius: '6px',
        transition: 'background-color 0.35s ease',
      }"
    >
      <div :style="{ display: 'flex' }">
        <span
          :style="{
            width: '1.4em',
            flex: 'none',
            color: t.colors.primary,
            fontWeight: block.ordered ? '600' : '400',
          }"
          >{{ block.ordered ? `${i + 1}.` : '•' }}</span
        >
        <span :style="{ flex: '1' }"><InlineText :text="item.text" /></span>
      </div>
      <div
        v-for="(sub, si) in item.sub ?? []"
        :key="si"
        :style="{
          paddingLeft: '2.8em',
          fontSize: `${smallPx}px`,
          color: t.colors.muted,
        }"
      >
        <InlineText :text="sub" />
      </div>
    </div>
  </div>
</template>
