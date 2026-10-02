<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { api, errorMessage } from '@/api/client'
import type {
  GraphView,
  AnnouncementView,
  CourseOutlineItemView,
  CourseOutlineView,
  CourseView,
} from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import CourseOutlineTree from '@/features/courses/components/CourseOutlineTree.vue'
import StudentReportPanel from '@/features/analytics/components/StudentReportPanel.vue'
import BlockCodingProjectList from '@/features/blockcoding/components/BlockCodingProjectList.vue'
import StudentCoursewareList from '@/features/courseware/components/StudentCoursewareList.vue'
import TutorAssistantList from '@/features/tutor/components/TutorAssistantList.vue'
import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import { formatDateTime } from '@/shared/format'
import { createLatestRequestGuard } from '@/shared/latestRequest'
import { openInNewTab } from '@/shared/openUrl'
import { tabFromQuery } from '@/shared/routeTab'

const tabs = new Set([
  'outline',
  'courseware',
  'introduction',
  'announcements',
  'knowledge-graphs',
  'my-analytics',
  'blockcoding',
  'tutor',
] as const)

const route = useRoute()
const router = useRouter()
const courseId = computed(() => Number(route.params.id))
const loading = ref(true)
const loadError = ref('')
const course = ref<CourseView>()
const outline = ref<CourseOutlineView>({ items: [], units: [] })
const announcements = ref<AnnouncementView[]>([])
const blockCodingEnabled = ref(false)
const knowledgeGraphs = ref<GraphView[]>([])
const activeTab = ref<string>(tabFromQuery(route.query, tabs, 'outline'))
const loadRequests = createLatestRequestGuard(() => courseId.value)

async function load(): Promise<void> {
  const request = loadRequests.begin()
  const requestedCourseId = request.snapshot
  course.value = undefined
  outline.value = { items: [], units: [] }
  announcements.value = []
  blockCodingEnabled.value = false
  knowledgeGraphs.value = []
  loadError.value = ''
  if (!Number.isSafeInteger(requestedCourseId) || requestedCourseId <= 0) {
    loadError.value = '当前课程地址无效'
    loading.value = false
    return
  }
  loading.value = true
  try {
    const [
      courseResponse,
      outlineResponse,
      announcementResponse,
      blockCodingResponse,
      knowledgeGraphResponse,
    ] = await Promise.all([
      api.courseGet(requestedCourseId),
      api.courseOutline(requestedCourseId),
      api.announcementList({ courseId: requestedCourseId, page: 1, size: 100 }),
      api.courseBlockCodingConfigGet(requestedCourseId),
      api.courseKnowledgeGraphList(requestedCourseId),
    ])
    if (!loadRequests.isCurrent(request)) return
    course.value = courseResponse.data
    outline.value = outlineResponse.data
    announcements.value = announcementResponse.data.items
    blockCodingEnabled.value = blockCodingResponse.data.enabled
    knowledgeGraphs.value = knowledgeGraphResponse.data
    if (activeTab.value === 'blockcoding' && !blockCodingEnabled.value) activeTab.value = 'outline'
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) {
      loading.value = false
    }
  }
}

async function openContent(item: CourseOutlineItemView): Promise<void> {
  try {
    if (item.itemType === 'material') {
      await openInNewTab(
        async () => (await api.courseMaterialCreateDownloadTicket(courseId.value, item.contentId)).data.url,
      )
      return
    }
    if (item.itemType === 'question') {
      await router.push(`/courses/${courseId.value}/questions/${item.contentId}`)
      return
    }
    await router.push(`/courses/${courseId.value}/problems/${item.contentId}`)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

watch(activeTab, (tab) => {
  if (route.query.tab === tab) return
  void router.replace({ query: { ...route.query, tab } })
})

watch(
  courseId,
  () => {
    activeTab.value = tabFromQuery(route.query, tabs, 'outline')
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <div class="page page--full">
    <AsyncState :loading="loading" :error="loadError" :empty="!course" @retry="load">
      <template v-if="course">
        <PageHeader :title="course.title">
          <template #actions>
            <el-button @click="router.push('/courses')">返回课程列表</el-button>
          </template>
        </PageHeader>

        <section class="panel course-content">
          <el-tabs v-model="activeTab">
            <el-tab-pane label="课程内容" name="outline">
              <CourseOutlineTree :outline="outline" @open="openContent" />
            </el-tab-pane>
            <el-tab-pane label="智能课堂" name="courseware">
              <StudentCoursewareList v-if="activeTab === 'courseware'" :course-id="courseId" />
            </el-tab-pane>
            <el-tab-pane label="课程介绍" name="introduction">
              <MarkdownRenderer
                v-if="course.descriptionMarkdown.trim()"
                :source="course.descriptionMarkdown"
              />
              <p v-else class="empty-text">暂无课程介绍</p>
            </el-tab-pane>
            <el-tab-pane label="课程公告" name="announcements">
              <div v-if="announcements.length" class="announcement-list">
                <article v-for="announcement in announcements" :key="announcement.id" class="announcement">
                  <header>
                    <h2>{{ announcement.title }}</h2>
                    <time>{{ formatDateTime(announcement.createdAt) }}</time>
                  </header>
                  <MarkdownRenderer :source="announcement.contentMarkdown" />
                </article>
              </div>
              <p v-else class="empty-text">暂无课程公告</p>
            </el-tab-pane>
            <el-tab-pane label="知识图谱" name="knowledge-graphs">
              <ul v-if="knowledgeGraphs.length" class="graph-list">
                <li v-for="graph in knowledgeGraphs" :key="graph.id">
                  <div class="graph-entry">
                    <div>
                      <strong>{{ graph.name }}</strong>
                    </div>
                    <el-button
                      link
                      type="primary"
                      @click="router.push(`/courses/${courseId}/knowledge-graphs/${graph.id}`)"
                    >
                      查看图谱
                    </el-button>
                  </div>
                </li>
              </ul>
              <p v-else class="empty-text">本课程还没有知识图谱</p>
            </el-tab-pane>
            <el-tab-pane label="我的学情" name="my-analytics">
              <StudentReportPanel v-if="activeTab === 'my-analytics'" :course-id="courseId" mode="me" />
            </el-tab-pane>
            <el-tab-pane v-if="blockCodingEnabled" label="积木编程" name="blockcoding">
              <BlockCodingProjectList v-if="activeTab === 'blockcoding'" :course-id="courseId" />
            </el-tab-pane>
            <el-tab-pane label="智能问答" name="tutor">
              <TutorAssistantList v-if="activeTab === 'tutor'" :course-id="courseId" />
            </el-tab-pane>
          </el-tabs>
        </section>
      </template>
    </AsyncState>
  </div>
</template>

<style scoped>
.graph-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 12px;
}

.graph-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
}

.course-content {
  padding: 8px 24px 24px;
}

.course-content :deep(.el-tabs__item) {
  height: 52px;
  font-weight: 600;
}

.announcement-list {
  display: grid;
  gap: 16px;
}

.announcement {
  padding: 18px 0;
  border-bottom: 1px solid var(--border);
}

.announcement:last-child {
  border-bottom: 0;
}

.announcement header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 12px;
}

.announcement h2 {
  margin: 0;
  font-size: 18px;
}

.announcement time {
  color: var(--text-muted);
  font-size: 12px;
}

.empty-text {
  margin: 0;
  padding: 44px 0;
  color: var(--text-muted);
  text-align: center;
}
</style>
