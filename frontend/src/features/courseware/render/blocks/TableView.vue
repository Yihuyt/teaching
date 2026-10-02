<script setup lang="ts">
/** 表格:单元格内边距(上下 7px、左右 10px)与 measureBlock 的 CELL_PADDING 对应 */
import type { TableBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

defineProps<{ block: TableBlock; fontScale: number }>()

const t = DEFAULT_THEME
</script>

<template>
  <div>
    <table
      :style="{
        width: '100%',
        borderCollapse: 'collapse',
        fontSize: `${t.fontSize.small * fontScale}px`,
        lineHeight: String(t.lineHeight),
        color: t.colors.text,
      }"
    >
      <thead>
        <tr>
          <th
            v-for="(h, i) in block.headers"
            :key="i"
            :style="{
              padding: `${7 * fontScale}px ${10 * fontScale}px`,
              border: `1px solid ${t.colors.tableBorder}`,
              background: t.colors.tableHeaderBackground,
              textAlign: 'left',
              fontWeight: '600',
            }"
          >
            <InlineText :text="h" />
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, ri) in block.rows" :key="ri">
          <td
            v-for="(cell, ci) in row"
            :key="ci"
            :style="{
              padding: `${7 * fontScale}px ${10 * fontScale}px`,
              border: `1px solid ${t.colors.tableBorder}`,
            }"
          >
            <InlineText :text="cell" />
          </td>
        </tr>
      </tbody>
    </table>
    <div
      v-if="block.caption"
      :style="{
        marginTop: `${t.spacing.xs * fontScale}px`,
        fontSize: `${t.fontSize.small * fontScale}px`,
        color: t.colors.muted,
        textAlign: 'center',
      }"
    >
      <InlineText :text="block.caption" />
    </div>
  </div>
</template>
