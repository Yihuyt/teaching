<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { ChartBlock } from '@/features/courseware/dsl'
import { DEFAULT_THEME } from '@/features/courseware/layout'
import { stripInline } from '@/features/courseware/inline'
import InlineText from '@/features/courseware/render/blocks/InlineText.vue'

const props = defineProps<{ block: ChartBlock; fontScale: number }>()

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

const PALETTE = DEFAULT_THEME.colors.chart

function buildOption(block: ChartBlock): echarts.EChartsOption {
  const textStyle = {
    fontSize: DEFAULT_THEME.fontSize.small * props.fontScale,
    color: DEFAULT_THEME.colors.text,
  }
  if (block.chartType === 'pie') {
    const first = block.series[0]
    return {
      color: PALETTE,
      tooltip: { show: false },
      legend: { bottom: 0, textStyle },
      series: [
        {
          type: 'pie',
          radius: ['35%', '68%'],
          label: { formatter: '{b}: {c}', fontSize: textStyle.fontSize },
          // ECharts 渲染不了内联语法,去标记后显示纯文本
          data: block.categories.map((name, i) => ({ name: stripInline(name), value: first?.data[i] ?? 0 })),
        },
      ],
    }
  }
  return {
    color: PALETTE,
    tooltip: { show: false },
    legend: block.series.length > 1 ? { top: 0, textStyle } : { show: false },
    grid: { left: 8, right: 16, top: block.series.length > 1 ? 36 : 16, bottom: 8, containLabel: true },
    xAxis: { type: 'category', data: block.categories.map(stripInline), axisLabel: textStyle },
    yAxis: { type: 'value', axisLabel: textStyle },
    series: block.series.map((s) => ({
      name: stripInline(s.name),
      type: block.chartType === 'bar' ? 'bar' : 'line',
      data: s.data,
      ...(block.chartType === 'line' ? { smooth: true } : {}),
    })),
  }
}

function render(): void {
  if (!el.value) return
  chart ??= echarts.init(el.value)
  chart.resize()
  chart.setOption(buildOption(props.block), { notMerge: true })
}

onMounted(render)
watch(() => props.block, render, { deep: true })
onBeforeUnmount(() => {
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div :style="{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column' }">
    <div ref="el" :style="{ flex: '1', minHeight: '0' }" />
    <div
      v-if="block.caption"
      :style="{
        marginTop: `${DEFAULT_THEME.spacing.xs * fontScale}px`,
        fontSize: `${DEFAULT_THEME.fontSize.small * fontScale}px`,
        color: DEFAULT_THEME.colors.muted,
        textAlign: 'center',
        flex: 'none',
      }"
    >
      <InlineText :text="block.caption" />
    </div>
  </div>
</template>
