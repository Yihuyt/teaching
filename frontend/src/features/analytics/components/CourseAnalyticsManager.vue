<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { HeatmapChart } from 'echarts/charts'
import { CanvasRenderer } from 'echarts/renderers'
import { GridComponent, TooltipComponent, VisualMapComponent } from 'echarts/components'

import { api, errorMessage } from '@/api/client'
import type {
  ClassOverview,
  ContentClassStat,
  NodeClassStat,
  NodeMasteryViewLevel,
  StudentContentStat,
  StudentSummary,
} from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import OutlineResultsDialog from '@/features/courses/components/OutlineResultsDialog.vue'
import StudentReportPanel from '@/features/analytics/components/StudentReportPanel.vue'
import { courseOutlineItemTypeLabels } from '@/shared/labels'
import { createLatestRequestGuard } from '@/shared/latestRequest'
import { masteryLevelColors, masteryLevelLabels, percentText } from '@/features/analytics/learningAnalytics'

echarts.use([HeatmapChart, CanvasRenderer, GridComponent, TooltipComponent, VisualMapComponent])

const props = defineProps<{ courseId: number }>()

const loading = ref(true)
const loadError = ref('')
const overview = ref<ClassOverview>()
const loadRequests = createLatestRequestGuard(() => props.courseId)
const tab = ref<'contents' | 'nodes' | 'students'>('contents')

async function load(): Promise<void> {
  const request = loadRequests.begin()
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.courseAnalyticsOverview(request.snapshot)
    if (!loadRequests.isCurrent(request)) return
    overview.value = response.data
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) loading.value = false
  }
}

const attention = computed(() => overview.value?.students.filter((s) => s.needsAttention) ?? [])
const studentNames = computed(() => {
  const names = new Map<number, string>()
  for (const student of overview.value?.students ?? []) names.set(student.accountId, student.displayName)
  return names
})

function contentKey(content: ContentClassStat): string {
  return `${content.itemType}:${content.contentId}`
}

function unitText(content: ContentClassStat): string {
  return content.units.map((unit) => (unit === '' ? '顶层' : unit)).join('；')
}

function completedStudents(content: ContentClassStat): StudentContentStat[] {
  return content.students.filter((s) => s.completed)
}

function pendingStudents(content: ContentClassStat): StudentContentStat[] {
  return content.students.filter((s) => !s.completed)
}

function studentStatText(student: StudentContentStat, content: ContentClassStat): string {
  const name = studentNames.value.get(student.accountId) ?? String(student.accountId)
  if (student.attempts === 0) return name
  const times =
    content.itemType === 'question' ? `作答 ${student.attempts} 次` : `提交 ${student.attempts} 次`
  return student.bestScore === null
    ? `${name} · ${times}`
    : `${name} · ${times} · 最高 ${percentText(student.bestScore)}`
}

const resultsVisible = ref(false)
const resultsContent = ref<ContentClassStat>()

function openResults(content: ContentClassStat): void {
  resultsContent.value = content
  resultsVisible.value = true
}

const levelOrder: readonly NodeMasteryViewLevel[] = ['WEAK', 'BASIC', 'PROFICIENT', 'TOUCHED', 'UNTOUCHED']

function levelCount(node: NodeClassStat, level: NodeMasteryViewLevel): number {
  switch (level) {
    case 'WEAK':
      return node.weakStudents
    case 'BASIC':
      return node.basicStudents
    case 'PROFICIENT':
      return node.proficientStudents
    case 'TOUCHED':
      return node.touchedStudents
    case 'UNTOUCHED':
      return node.untouchedStudents
  }
}

const assessableNodes = computed(() =>
  (overview.value?.nodes ?? [])
    .filter((node) => node.assessable)
    .toSorted(
      (left, right) =>
        right.weakStudents - left.weakStudents || (left.averageScore ?? 1) - (right.averageScore ?? 1),
    ),
)
const unassessableCount = computed(
  () => (overview.value?.nodes ?? []).filter((node) => !node.assessable).length,
)

function segments(node: NodeClassStat): Array<{ level: NodeMasteryViewLevel; count: number; width: string }> {
  const total = overview.value?.studentCount ?? 0
  return levelOrder
    .map((level) => ({ level, count: levelCount(node, level) }))
    .filter((segment) => segment.count > 0)
    .map((segment) => ({ ...segment, width: `${(segment.count / total) * 100}%` }))
}

const heatHost = ref<HTMLDivElement>()
let heatChart: echarts.ECharts | undefined
const studentLevels = ref<Map<number, Map<string, number | null>>>(new Map())
const heatLoading = ref(false)

async function loadHeatmap(): Promise<void> {
  if (!overview.value) return
  heatLoading.value = true
  try {
    const entries = await Promise.all(
      overview.value.students.map(async (student) => {
        const report = (await api.courseAnalyticsStudent(props.courseId, student.accountId)).data
        const scores = new Map<string, number | null>()
        for (const node of report.mastery) scores.set(`${node.graphId}:${node.nodeId}`, node.score)
        return [student.accountId, scores] as const
      }),
    )
    studentLevels.value = new Map(entries)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    heatLoading.value = false
  }
}

function renderHeatmap(): void {
  if (!heatHost.value || !overview.value) return
  const nodes = assessableNodes.value
  const students = overview.value.students
  if (nodes.length === 0 || students.length === 0 || studentLevels.value.size === 0) {
    heatChart?.clear()
    return
  }
  const data: Array<[number, number, number]> = []
  students.forEach((student, y) => {
    const scores = studentLevels.value.get(student.accountId)
    nodes.forEach((node, x) => {
      const score = scores?.get(`${node.graphId}:${node.nodeId}`)
      data.push([x, y, score === null || score === undefined ? -1 : score])
    })
  })
  heatChart ??= echarts.init(heatHost.value)
  heatChart.setOption(
    {
      tooltip: {
        formatter: (params: { value: [number, number, number] }) => {
          const [x, y, v] = params.value
          const node = nodes[x]
          const student = students[y]
          if (!node || !student) return ''
          return `${student.displayName} · ${node.label}<br/>${v < 0 ? '未接触' : percentText(v)}`
        },
      },
      grid: { left: 8, right: 16, top: 8, bottom: 80, containLabel: true },
      xAxis: { type: 'category', data: nodes.map((n) => n.label), axisLabel: { rotate: 40, interval: 0 } },
      yAxis: { type: 'category', data: students.map((s) => s.displayName) },
      visualMap: {
        type: 'piecewise',
        orient: 'horizontal',
        left: 'center',
        bottom: 0,
        pieces: [
          { lt: 0, color: masteryLevelColors.UNTOUCHED, label: masteryLevelLabels.UNTOUCHED },
          { gte: 0, lt: 0.4, color: masteryLevelColors.WEAK, label: masteryLevelLabels.WEAK },
          { gte: 0.4, lt: 0.75, color: masteryLevelColors.BASIC, label: masteryLevelLabels.BASIC },
          { gte: 0.75, color: masteryLevelColors.PROFICIENT, label: masteryLevelLabels.PROFICIENT },
        ],
      },
      series: [{ type: 'heatmap', data, emphasis: { itemStyle: { borderColor: '#111', borderWidth: 1 } } }],
    },
    true,
  )
  heatChart.resize()
}

let observer: ResizeObserver | undefined

function observe(host: HTMLDivElement | undefined): void {
  if (!host) return
  observer ??= new ResizeObserver(() => heatChart?.resize())
  observer.observe(host)
}

watch(overview, () => loadHeatmap(), { flush: 'post' })
watch(studentLevels, renderHeatmap, { flush: 'post' })
/** 页签切换后容器才有尺寸:切到知识点页时再画热力图 */
watch(tab, async (current) => {
  await nextTick()
  if (current === 'nodes') {
    observe(heatHost.value)
    renderHeatmap()
  }
})
onBeforeUnmount(() => {
  observer?.disconnect()
  heatChart?.dispose()
})

const drawerVisible = ref(false)
const drawerStudent = ref<StudentSummary>()

function openStudent(student: StudentSummary): void {
  drawerStudent.value = student
  drawerVisible.value = true
}

function exportXlsx(): void {
  window.location.assign(`/api/v1/courses/${props.courseId}/analytics/export.xlsx`)
}

watch(
  () => props.courseId,
  () => {
    drawerVisible.value = false
    resultsVisible.value = false
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <section>
    <AsyncState :loading="loading" :error="loadError" :empty="!overview" @retry="load">
      <template v-if="overview">
        <div class="toolbar">
          <el-button :icon="Download" @click="exportXlsx">导出 Excel</el-button>
        </div>

        <div class="metric-grid">
          <div class="metric-card">
            <div>
              <strong>{{ overview.studentCount }}</strong
              ><span>学生人数</span>
            </div>
          </div>
          <div class="metric-card">
            <div>
              <strong>{{ percentText(overview.averageProgress) }}</strong
              ><span>平均进度</span>
            </div>
          </div>
          <div class="metric-card">
            <div>
              <strong>{{ percentText(overview.averageMastery) }}</strong
              ><span>平均掌握度</span>
            </div>
          </div>
        </div>

        <section v-if="attention.length" class="block">
          <h4>需要关注({{ attention.length }})</h4>
          <div class="attention">
            <el-tag
              v-for="student in attention"
              :key="student.accountId"
              type="warning"
              effect="plain"
              class="attention-tag"
              @click="openStudent(student)"
            >
              {{ student.displayName }}
              <span class="attention-why"> 薄弱 {{ student.weakCount }} 个 </span>
            </el-tag>
          </div>
        </section>

        <el-tabs v-model="tab">
          <el-tab-pane label="内容" name="contents">
            <p v-if="overview.contents.length === 0" class="hint">课程内容里没有试题或编程题</p>
            <el-table v-else :data="overview.contents" size="small" :row-key="contentKey">
              <el-table-column type="expand">
                <template #default="{ row }: { row: ContentClassStat }">
                  <div class="expand">
                    <div class="expand-group">
                      <strong>已完成 {{ completedStudents(row).length }} 人</strong>
                      <span v-if="completedStudents(row).length === 0" class="hint">—</span>
                      <span
                        v-for="student in completedStudents(row)"
                        :key="student.accountId"
                        class="expand-item"
                      >
                        {{ studentStatText(student, row) }}
                      </span>
                    </div>
                    <div class="expand-group">
                      <strong>未完成 {{ pendingStudents(row).length }} 人</strong>
                      <span v-if="pendingStudents(row).length === 0" class="hint">—</span>
                      <span
                        v-for="student in pendingStudents(row)"
                        :key="student.accountId"
                        class="expand-item"
                      >
                        {{ studentStatText(student, row) }}
                      </span>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="title" label="名称" min-width="160" show-overflow-tooltip />
              <el-table-column label="类型" width="80">
                <template #default="{ row }: { row: ContentClassStat }">{{
                  courseOutlineItemTypeLabels[row.itemType]
                }}</template>
              </el-table-column>
              <el-table-column label="所属单元" min-width="120" show-overflow-tooltip>
                <template #default="{ row }: { row: ContentClassStat }">{{ unitText(row) }}</template>
              </el-table-column>
              <el-table-column label="完成" width="150">
                <template #default="{ row }: { row: ContentClassStat }">
                  <div class="completion">
                    <el-progress
                      :percentage="
                        overview.studentCount
                          ? Math.round((row.completedStudents / overview.studentCount) * 100)
                          : 0
                      "
                      :stroke-width="8"
                      :show-text="false"
                    />
                    <span class="completion-text"
                      >{{ row.completedStudents }}/{{ overview.studentCount }}</span
                    >
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="attemptedStudents" label="作答人数" width="84" />
              <el-table-column label="平均得分" width="84">
                <template #default="{ row }: { row: ContentClassStat }">{{
                  percentText(row.averageScore)
                }}</template>
              </el-table-column>
              <el-table-column prop="attemptCount" label="作答次数" width="84" />
              <el-table-column label="" width="64" align="right">
                <template #default="{ row }: { row: ContentClassStat }">
                  <el-button link type="primary" @click="openResults(row)">成绩</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="知识点" name="nodes">
            <p v-if="overview.nodes.length === 0" class="hint">课程还没有知识图谱</p>
            <template v-else>
              <p v-if="assessableNodes.length === 0" class="hint">
                没有挂载试题或编程题的知识点,无法评估掌握度
              </p>
              <div v-else class="node-list">
                <div
                  v-for="node in assessableNodes"
                  :key="`${node.graphId}:${node.nodeId}`"
                  class="node-row"
                >
                  <span class="node-label">{{ node.label }}</span>
                  <div class="node-bar" :title="`平均 ${percentText(node.averageScore)}`">
                    <span
                      v-for="segment in segments(node)"
                      :key="segment.level"
                      class="node-segment"
                      :style="{ width: segment.width, background: masteryLevelColors[segment.level] }"
                      :title="`${masteryLevelLabels[segment.level]} ${segment.count} 人`"
                    ></span>
                  </div>
                  <span class="hint node-summary"
                    >平均 {{ percentText(node.averageScore) }} · 薄弱 {{ node.weakStudents }} 人 · 未接触
                    {{ node.untouchedStudents }} 人</span
                  >
                </div>
                <div class="legend">
                  <span v-for="level in levelOrder" :key="level" class="legend-item">
                    <span class="node-dot" :style="{ background: masteryLevelColors[level] }"></span
                    >{{ masteryLevelLabels[level] }}
                  </span>
                </div>
              </div>
              <p v-if="unassessableCount" class="hint">
                另有 {{ unassessableCount }} 个知识点未挂载试题或编程题,不评估
              </p>

              <section class="block heat-block">
                <h4>知识点 × 学生</h4>
                <p v-if="assessableNodes.length === 0 || overview.students.length === 0" class="hint">
                  需要有学生且知识点挂载了试题或编程题
                </p>
                <div
                  v-show="assessableNodes.length && overview.students.length"
                  ref="heatHost"
                  v-loading="heatLoading"
                  class="chart"
                  :style="{ height: `${120 + overview.students.length * 36}px` }"
                ></div>
              </section>
            </template>
          </el-tab-pane>

          <el-tab-pane label="学生" name="students">
            <el-table :data="overview.students" size="small" @row-click="openStudent">
              <el-table-column prop="displayName" label="姓名" min-width="140" />
              <el-table-column label="进度" width="100">
                <template #default="{ row }: { row: StudentSummary }">{{
                  percentText(row.progressPercent)
                }}</template>
              </el-table-column>
              <el-table-column label="掌握度" width="100">
                <template #default="{ row }: { row: StudentSummary }">{{
                  percentText(row.masteryAverage)
                }}</template>
              </el-table-column>
              <el-table-column prop="weakCount" label="薄弱知识点" width="110" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }: { row: StudentSummary }">
                  <el-tag v-if="row.needsAttention" size="small" type="warning">需关注</el-tag>
                  <span v-else class="hint">正常</span>
                </template>
              </el-table-column>
              <el-table-column label="" width="80" align="right">
                <template #default="{ row }: { row: StudentSummary }">
                  <el-button link type="primary" @click.stop="openStudent(row)">详情</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </AsyncState>

    <OutlineResultsDialog v-model="resultsVisible" :course-id="courseId" :item="resultsContent" />

    <el-drawer
      v-model="drawerVisible"
      :title="drawerStudent ? `${drawerStudent.displayName} 的学情` : '学情'"
      size="720px"
      destroy-on-close
    >
      <StudentReportPanel
        v-if="drawerStudent"
        :course-id="courseId"
        mode="teacher"
        :account-id="drawerStudent.accountId"
      />
    </el-drawer>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 14px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: #fff;
  margin-bottom: 18px;
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

.attention {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.attention-tag {
  cursor: pointer;
}

.attention-why {
  margin-left: 6px;
  opacity: 0.8;
}

.chart {
  width: 100%;
}

.completion {
  display: flex;
  align-items: center;
  gap: 8px;
}

.completion :deep(.el-progress) {
  flex: 1;
}

.completion-text {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

.expand {
  display: grid;
  gap: 8px;
  padding: 4px 12px 8px 48px;
  font-size: 13px;
}

.expand-group {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 14px;
}

.expand-item {
  color: var(--text-muted);
}

.node-list {
  display: grid;
  gap: 8px;
}

.node-row {
  display: grid;
  grid-template-columns: minmax(120px, 200px) minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.node-label {
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-bar {
  display: flex;
  height: 12px;
  overflow: hidden;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}

.node-segment {
  display: block;
  height: 100%;
}

.node-summary {
  white-space: nowrap;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.node-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.heat-block {
  margin-top: 22px;
}
</style>
