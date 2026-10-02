<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { RadarChart } from 'echarts/charts'
import { CanvasRenderer } from 'echarts/renderers'
import { TooltipComponent } from 'echarts/components'

import { api, errorMessage } from '@/api/client'
import type { NodeMasteryView, StudentReport } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import { courseOutlineItemTypeLabels } from '@/shared/labels'
import {
  classPositionLabels,
  masteryLevelLabels,
  masteryLevelTagTypes,
  percentText,
  radarNodes,
} from '@/features/analytics/learningAnalytics'

echarts.use([RadarChart, CanvasRenderer, TooltipComponent])

const props = defineProps<{
  courseId: number
  mode: 'teacher' | 'me'
  accountId?: number
}>()

const router = useRouter()
const loading = ref(true)
const loadError = ref('')
const report = ref<StudentReport>()

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    if (props.mode === 'teacher') {
      if (props.accountId === undefined) throw new Error('教师视图必须指定学生')
      report.value = (await api.courseAnalyticsStudent(props.courseId, props.accountId)).data
    } else {
      report.value = (await api.courseAnalyticsMe(props.courseId)).data
    }
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

const radarHost = ref<HTMLDivElement>()
let radar: echarts.ECharts | undefined
let observer: ResizeObserver | undefined

const radarData = computed(() => (report.value ? radarNodes(report.value.mastery, 10) : []))

function renderRadar(): void {
  if (!radarHost.value) return
  if (radarData.value.length < 3) {
    radar?.clear()
    return
  }
  radar ??= echarts.init(radarHost.value)
  radar.setOption(
    {
      tooltip: {},
      radar: {
        indicator: radarData.value.map((node) => ({ name: node.label, max: 1 })),
        radius: '65%',
        axisName: { color: '#4b5563', fontSize: 12 },
      },
      series: [
        {
          type: 'radar',
          areaStyle: { opacity: 0.25 },
          lineStyle: { color: '#2563eb' },
          itemStyle: { color: '#2563eb' },
          data: [{ value: radarData.value.map((node) => node.score ?? 0), name: '掌握度' }],
        },
      ],
    },
    true,
  )
}

onMounted(() => {
  void load()
  if (radarHost.value) {
    observer = new ResizeObserver(() => radar?.resize())
    observer.observe(radarHost.value)
  }
})
watch(radarData, renderRadar, { flush: 'post' })
watch(
  () => [props.courseId, props.accountId, props.mode],
  () => void load(),
)
onBeforeUnmount(() => {
  observer?.disconnect()
  radar?.dispose()
})

const weakFirst = computed(() =>
  report.value
    ? report.value.mastery.toSorted((left, right) => {
        const order = { WEAK: 0, BASIC: 1, TOUCHED: 2, UNTOUCHED: 3, PROFICIENT: 4 }
        return order[left.level] - order[right.level] || (left.score ?? 0) - (right.score ?? 0)
      })
    : [],
)

function openNode(node: NodeMasteryView): void {
  const target =
    props.mode === 'me'
      ? `/courses/${props.courseId}/knowledge-graphs/${node.graphId}`
      : `/focus/admin/courses/${props.courseId}/knowledge-graphs/${node.graphId}/edit`
  void router.push({ path: target, query: { node: String(node.nodeId) } })
}
</script>

<template>
  <AsyncState :loading="loading" :error="loadError" :empty="!report" @retry="load">
    <template v-if="report">
      <div class="metric-grid">
        <div class="metric-card">
          <div>
            <strong>{{
              percentText(
                report.progress.totalItems
                  ? report.progress.completedItems / report.progress.totalItems
                  : null,
              )
            }}</strong>
            <span>课程内容完成 {{ report.progress.completedItems }}/{{ report.progress.totalItems }}</span>
          </div>
        </div>
        <div class="metric-card">
          <div>
            <strong>{{ percentText(report.masteryAverage) }}</strong>
            <span>综合掌握度 · 薄弱 {{ report.weakCount }} 个</span>
          </div>
        </div>
      </div>

      <div v-if="mode === 'me' && report.classPosition" class="position-bar">
        <el-tag
          effect="dark"
          :type="
            report.classPosition === 'TOP'
              ? 'success'
              : report.classPosition === 'BOTTOM'
                ? 'warning'
                : 'primary'
          "
        >
          {{ classPositionLabels[report.classPosition] }}
        </el-tag>
      </div>

      <section class="block">
        <h4>内容进度</h4>
        <p v-if="report.progress.units.length === 0 && report.progress.items.length === 0" class="hint">
          内容为空
        </p>
        <ul v-if="report.progress.items.length" class="items">
          <li v-for="item in report.progress.items" :key="item.itemId">
            <el-tag size="small" :type="item.completed ? 'success' : 'info'" effect="plain">
              {{ item.completed ? '已完成' : '未完成' }}
            </el-tag>
            <span class="item-type">{{ courseOutlineItemTypeLabels[item.itemType] }}</span>
            <span>{{ item.title }}</span>
          </li>
        </ul>
        <div
          v-for="unit in report.progress.units"
          :key="unit.unitId"
          class="unit"
          :style="{ paddingLeft: `${unit.depth * 18}px` }"
        >
          <strong class="unit-head">{{ unit.title }}</strong>
          <ul v-if="unit.items.length" class="items">
            <li v-for="item in unit.items" :key="item.itemId">
              <el-tag size="small" :type="item.completed ? 'success' : 'info'" effect="plain">
                {{ item.completed ? '已完成' : '未完成' }}
              </el-tag>
              <span class="item-type">{{ courseOutlineItemTypeLabels[item.itemType] }}</span>
              <span>{{ item.title }}</span>
            </li>
          </ul>
        </div>
      </section>

      <section class="block">
        <h4>知识点掌握</h4>
        <p v-if="report.mastery.length === 0" class="hint">课程尚未建立知识图谱或图谱节点未挂载资源</p>
        <template v-else>
          <div v-show="radarData.length >= 3" ref="radarHost" class="radar"></div>
          <el-table :data="weakFirst" size="small">
            <el-table-column label="知识点" min-width="200">
              <template #default="{ row }: { row: NodeMasteryView }">
                <el-button link type="primary" @click="openNode(row)">{{ row.label }}</el-button>
                <el-tag v-if="row.rootCause" size="small" type="danger" effect="dark" class="root-tag"
                  >根因</el-tag
                >
              </template>
            </el-table-column>
            <el-table-column label="档位" width="110">
              <template #default="{ row }: { row: NodeMasteryView }">
                <el-tag size="small" :type="masteryLevelTagTypes[row.level]">{{
                  masteryLevelLabels[row.level]
                }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="得分" width="80">
              <template #default="{ row }: { row: NodeMasteryView }">{{ percentText(row.score) }}</template>
            </el-table-column>
            <el-table-column label="挂载资源" min-width="220">
              <template #default="{ row }: { row: NodeMasteryView }">
                <span v-if="row.resources.length === 0" class="hint">—</span>
                <span
                  v-for="resource in row.resources"
                  :key="`${resource.itemType}-${resource.contentId}`"
                  class="resource"
                >
                  {{ resource.title }}
                </span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </section>
    </template>
  </AsyncState>
</template>

<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: #fff;
  margin-bottom: 16px;
}

.metric-card {
  padding: 18px 22px;
  border-right: 1px solid var(--border);
}

.metric-card:last-child {
  border-right: 0;
}

.metric-card strong,
.metric-card span {
  display: block;
}

.metric-card strong {
  font-size: 26px;
}

.metric-card span {
  margin-top: 4px;
  color: var(--text-muted);
  font-size: 13px;
}

.position-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.block {
  margin-bottom: 22px;
}

.block h4 {
  margin: 0 0 10px;
  font-size: 15px;
}

.hint {
  color: var(--text-muted);
  font-size: 13px;
}

.unit {
  margin-bottom: 12px;
}

.unit-head {
  display: block;
  margin-bottom: 4px;
}

.items {
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  display: grid;
  gap: 4px;
  font-size: 13px;
}

.items li {
  display: flex;
  align-items: center;
  gap: 8px;
}

.item-type {
  color: var(--text-muted);
}

.radar {
  height: 320px;
  margin-bottom: 10px;
}

.root-tag {
  margin-left: 6px;
}

.resource {
  display: inline-block;
  margin-right: 10px;
  font-size: 12px;
  color: var(--text-muted);
}

</style>
