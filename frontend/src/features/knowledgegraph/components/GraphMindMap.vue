<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { TreeChart } from 'echarts/charts'
import { CanvasRenderer } from 'echarts/renderers'
import { TooltipComponent } from 'echarts/components'

import {
  ancestorIds,
  buildTree,
  categoryColor,
  type MindMapItem,
  type TreeNode,
} from '@/features/knowledgegraph/knowledgeGraph'

echarts.use([TreeChart, CanvasRenderer, TooltipComponent])

const props = defineProps<{
  rootLabel: string
  items: MindMapItem[]
  selectedId: string | null
  highlights?: Record<string, 'prerequisite' | 'successor' | 'related'>
  /** 类别过滤:空 = 全部显示 */
  visibleCategories?: string[] | undefined
}>()

const emit = defineEmits<{
  select: [id: string | null]
  /** 右键:节点 id(空白处为 null)与屏幕坐标 */
  context: [id: string | null, x: number, y: number]
  open: [id: string]
}>()

const host = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
let observer: ResizeObserver | undefined
let nodeContextHandled = false
let rendered = false
const collapsed = new Set<string>()
let collapsedInitialized = false

// ECharts 的视图缩放只平移节点位置,标签(textContent)从不跟随缩放,也没有配置项能改;
// 所以字号等尺寸统一由缩放倍数推导。倍数与 ECharts 内部完全同步:同一初值、
// 同一增量(treeroam 事件的 zoom)、同一钳制(series.scaleLimit)。
const SCALE_LIMIT = { min: 0.2, max: 4 }
// 布局空间随可见节点数生长:每个可见叶子一份固定行距、每层一份固定列宽,
// 节点多时布局自动变高变宽(靠漫游查看),而不是把所有节点压进固定画布挤成一团
const LEAF_ROW_HEIGHT = 30
const LEVEL_COLUMN_WIDTH = 260
let zoom = 1
let roamFrame: number | undefined

function onRoam(params: { zoom?: number }): void {
  if (params.zoom == null) return
  zoom = Math.min(SCALE_LIMIT.max, Math.max(SCALE_LIMIT.min, zoom * params.zoom))
  if (roamFrame === undefined) {
    roamFrame = requestAnimationFrame(() => {
      roamFrame = undefined
      render()
    })
  }
}

const HIGHLIGHT_COLORS = { prerequisite: '#d94848', successor: '#1f6feb', related: '#7c5cff' }

interface TreeSeriesNode {
  name: string
  id: string
  itemStyle: { color: string; borderColor?: string; borderWidth?: number }
  label: { backgroundColor: string; borderColor?: string; borderWidth?: number; borderType?: string }
  children?: TreeSeriesNode[]
  collapsed?: boolean
}

/** 首次渲染:第 3 层起默认折叠,之后只按用户操作与 reveal 变化 */
function initializeCollapsed(node: TreeNode, depth: number): void {
  if (depth >= 2 && node.children.length > 0) collapsed.add(node.id)
  node.children.forEach((child) => initializeCollapsed(child, depth + 1))
}

function visibleLeaves(node: TreeNode): number {
  if (node.children.length === 0 || collapsed.has(node.id)) return 1
  return node.children.reduce((sum, child) => sum + visibleLeaves(child), 0)
}

function visibleDepth(node: TreeNode): number {
  if (node.children.length === 0 || collapsed.has(node.id)) return 1
  return 1 + Math.max(...node.children.map(visibleDepth))
}

function toSeries(node: TreeNode, depth: number): TreeSeriesNode | null {
  const visible = props.visibleCategories
  const children = node.children
    .map((child) => toSeries(child, depth + 1))
    .filter((child): child is TreeSeriesNode => child !== null)
  // 过滤只作用于叶子:有可见后代的中间节点保留(否则整棵树断链)
  if (visible && visible.length > 0 && node.id !== '' && !visible.includes(node.category) && children.length === 0) {
    return null
  }
  const color = node.id === '' ? '#1f6feb' : categoryColor(node.category)
  const highlight = node.id === '' ? undefined : props.highlights?.[node.id]
  const selected = node.id !== '' && node.id === props.selectedId
  const label: TreeSeriesNode['label'] = { backgroundColor: color }
  if (selected) Object.assign(label, { borderColor: '#111', borderWidth: 2 })
  else if (highlight) Object.assign(label, { borderColor: HIGHLIGHT_COLORS[highlight], borderWidth: 2, borderType: 'dashed' })
  return {
    name: node.label,
    id: node.id,
    itemStyle: { color },
    label,
    ...(children.length ? { children } : {}),
    ...(children.length && collapsed.has(node.id) ? { collapsed: true } : {}),
  }
}

function render(): void {
  if (!chart) return
  const tree = buildTree(props.rootLabel, props.items)
  if (!collapsedInitialized) {
    initializeCollapsed(tree, 0)
    collapsedInitialized = true
  }
  const data = toSeries(tree, 0)
  if (!data) return
  const layoutHeight = Math.max(chart.getHeight() - 40, visibleLeaves(tree) * LEAF_ROW_HEIGHT)
  const layoutWidth = Math.max(chart.getWidth() - 140, visibleDepth(tree) * LEVEL_COLUMN_WIDTH)
  // 首次全量,之后合并更新:整棵树重建会把用户的缩放 / 平移拍回原位
  chart.setOption(
    {
      tooltip: {
        renderMode: 'richText',
        formatter: (params: { name?: string }) => params.name ?? '',
      },
      series: [
        {
          type: 'tree',
          data: [data],
          orient: 'LR',
          layout: 'orthogonal',
          top: 20,
          left: 70,
          width: layoutWidth,
          height: layoutHeight,
          roam: true,
          initialTreeDepth: -1,
          expandAndCollapse: true,
          symbol: 'circle',
          symbolSize: 10,
          // 圆点符号随视图等比缩放(标签尺寸在下面按 zoom 推导,二者口径一致)
          nodeScaleRatio: 1,
          scaleLimit: SCALE_LIMIT,
          edgeShape: 'curve',
          lineStyle: { color: '#c4cbd6', width: 1.4 },
          label: {
            position: 'inside',
            color: '#fff',
            fontSize: 12 * zoom,
            padding: [5 * zoom, 10 * zoom],
            borderRadius: 12 * zoom,
            formatter: (params: { name?: string }) =>
              (params.name ?? '').length > 18 ? `${(params.name ?? '').slice(0, 18)}…` : (params.name ?? ''),
          },
          leaves: { label: { position: 'inside' } },
          animationDuration: 260,
          animationDurationUpdate: 260,
        },
      ],
    },
    !rendered,
  )
  rendered = true
}

/** 点击 = 选中 + (有子节点时)切换折叠;ECharts 自身也会切换,这里同步记录以便重绘不塌回去(根节点同样记账) */
function onClick(params: { data?: { id?: string; children?: unknown[] } }): void {
  const id = params.data?.id
  if (id === undefined) return
  if (params.data?.children?.length) {
    if (collapsed.has(id)) collapsed.delete(id)
    else collapsed.add(id)
  }
  emit('select', id === '' ? null : id)
}

function reveal(id: string): void {
  for (const ancestor of ancestorIds(props.items, id)) collapsed.delete(ancestor)
  render()
}

function expandAll(): void {
  collapsed.clear()
  render()
}

function collapseAll(): void {
  collapsed.clear()
  initializeCollapsed(buildTree(props.rootLabel, props.items), 0)
  render()
}

/** 节点上的右键:阻止浏览器菜单,交给父组件出操作菜单;空白处的右键由 zrender 兜住 */
function onContextMenu(params: { data?: { id?: string }; event?: { event?: MouseEvent } }): void {
  const raw = params.event?.event
  raw?.preventDefault()
  nodeContextHandled = true
  const id = params.data?.id
  emit('context', id === undefined || id === '' ? null : id, raw?.clientX ?? 0, raw?.clientY ?? 0)
}

function onDoubleClick(params: { data?: { id?: string } }): void {
  const id = params.data?.id
  if (id) emit('open', id)
}

defineExpose({ reveal })

onMounted(() => {
  if (!host.value) throw new Error('导图画布挂载节点不存在')
  chart = echarts.init(host.value)
  chart.on('click', onClick as never)
  chart.on('contextmenu', onContextMenu as never)
  chart.on('dblclick', onDoubleClick as never)
  chart.on('treeroam', onRoam as never)
  chart.getZr().on('contextmenu', (event: { event: MouseEvent }) => {
    event.event.preventDefault()
    if (nodeContextHandled) {
      nodeContextHandled = false
      return
    }
    emit('context', null, event.event.clientX, event.event.clientY)
  })
  render()
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(host.value)
})

watch(() => [props.items, props.visibleCategories, props.selectedId, props.highlights], render)

onBeforeUnmount(() => {
  if (roamFrame !== undefined) cancelAnimationFrame(roamFrame)
  observer?.disconnect()
  chart?.dispose()
  chart = undefined
})
</script>

<template>
  <div class="mind-map">
    <div ref="host" class="mind-map__canvas" />
    <div class="mind-map__tools">
      <el-button size="small" @click="expandAll">展开全部</el-button>
      <el-button size="small" @click="collapseAll">收起</el-button>
    </div>
  </div>
</template>

<style scoped>
.mind-map {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 520px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: #fff;
}

.mind-map__canvas {
  position: absolute;
  inset: 0;
}

.mind-map__tools {
  position: absolute;
  top: 10px;
  right: 10px;
  display: flex;
  gap: 6px;
}

.mind-map__tools .el-button + .el-button {
  margin-left: 0;
}
</style>
