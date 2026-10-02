<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { api, errorMessage } from '@/api/client'
import type { CourseManagementView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import CourseAnnouncementManager from '@/features/courses/components/CourseAnnouncementManager.vue'
import CourseBlockCodingManager from '@/features/blockcoding/components/CourseBlockCodingManager.vue'
import QuestionGenerationPanel from '@/features/courses/components/generation/QuestionGenerationPanel.vue'
import CourseKnowledgeBasesManager from '@/features/knowledgebase/components/CourseKnowledgeBasesManager.vue'
import CourseTutorManager from '@/features/tutor/components/CourseTutorManager.vue'
import CourseKnowledgeGraphsManager from '@/features/knowledgegraph/components/CourseKnowledgeGraphsManager.vue'
import CourseAnalyticsManager from '@/features/analytics/components/CourseAnalyticsManager.vue'
import CourseMembersManager from '@/features/courses/components/CourseMembersManager.vue'
import CourseOutlineManager from '@/features/courses/components/CourseOutlineManager.vue'
import CourseResourcesManager from '@/features/courses/components/CourseResourcesManager.vue'
import CoursewareManager from '@/features/courseware/components/CoursewareManager.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import { createLatestRequestGuard } from '@/shared/latestRequest'
import { tabFromQuery } from '@/shared/routeTab'

const route = useRoute()
const router = useRouter()
const courseId = computed(() => Number(route.params.courseId))
const loading = ref(true)
const loadError = ref('')
const course = ref<CourseManagementView>()
const tabs = new Set([
  'resources',
  'outline',
  'knowledge-graphs',
  'knowledge-bases',
  'tutor',
  'generation',
  'courseware',
  'analytics',
  'blockcoding',
  'members',
  'announcements',
])
const activeTab = ref(tabFromQuery(route.query, tabs, 'resources'))
const loadRequests = createLatestRequestGuard(() => courseId.value)

function updateJoinCode(joinCode: string): void {
  if (!course.value) return
  course.value = { ...course.value, joinCode }
}

function hasValidCourseId(value: number): boolean {
  return Number.isSafeInteger(value) && value > 0
}

async function load(): Promise<void> {
  const request = loadRequests.begin()
  const requestedCourseId = request.snapshot
  course.value = undefined
  loadError.value = ''
  if (!hasValidCourseId(requestedCourseId)) {
    loading.value = false
    loadError.value = '课程编号无效'
    return
  }
  loading.value = true
  try {
    const response = await api.courseGetForManagement(requestedCourseId)
    if (!loadRequests.isCurrent(request)) return
    course.value = response.data
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) {
      loading.value = false
    }
  }
}

watch(
  courseId,
  () => {
    activeTab.value = tabFromQuery(route.query, tabs, 'resources')
    void load()
  },
  { immediate: true },
)

watch(activeTab, (tab) => {
  if (route.query.tab === tab) return
  void router.replace({ query: { ...route.query, tab } })
})
</script>

<template>
  <div class="course-page">
    <PageHeader :title="course?.title ?? '课程管理'">
      <template #title-extra>
        <el-tag v-if="course" :type="course.published ? 'success' : 'info'" effect="plain" size="small">
          {{ course.published ? '已发布' : '未发布' }}
        </el-tag>
      </template>
      <template #actions>
        <el-button @click="router.push('/admin/courses')">返回课程列表</el-button>
      </template>
    </PageHeader>

    <AsyncState :loading="loading" :error="loadError" :empty="!course" @retry="load">
      <section v-if="course" class="panel course-workspace">
        <el-tabs v-model="activeTab" tab-position="left" class="course-tabs">
          <el-tab-pane label="资料库" name="resources">
            <CourseResourcesManager v-if="activeTab === 'resources'" :course-id="course.id" />
          </el-tab-pane>
          <el-tab-pane label="课程内容" name="outline">
            <CourseOutlineManager v-if="activeTab === 'outline'" :course-id="course.id" />
          </el-tab-pane>
          <el-tab-pane label="知识图谱" name="knowledge-graphs">
            <CourseKnowledgeGraphsManager v-if="activeTab === 'knowledge-graphs'" :course-id="course.id" />
          </el-tab-pane>
          <el-tab-pane label="知识库管理" name="knowledge-bases">
            <CourseKnowledgeBasesManager v-if="activeTab === 'knowledge-bases'" :course-id="course.id" />
          </el-tab-pane>
          <el-tab-pane label="智能问答" name="tutor">
            <CourseTutorManager v-if="activeTab === 'tutor'" :course-id="course.id" />
          </el-tab-pane>
          <el-tab-pane label="资源生成" name="generation">
            <QuestionGenerationPanel v-if="activeTab === 'generation'" :course-id="course.id" />
          </el-tab-pane>
          <el-tab-pane label="智能课堂" name="courseware">
            <CoursewareManager v-if="activeTab === 'courseware'" :course-id="course.id" />
          </el-tab-pane>
          <el-tab-pane label="学情" name="analytics">
            <CourseAnalyticsManager v-if="activeTab === 'analytics'" :course-id="course.id" />
          </el-tab-pane>
          <el-tab-pane label="积木编程" name="blockcoding">
            <CourseBlockCodingManager v-if="activeTab === 'blockcoding'" :course-id="course.id" />
          </el-tab-pane>
          <el-tab-pane label="成员与课程码" name="members">
            <CourseMembersManager
              v-if="activeTab === 'members'"
              :course-id="course.id"
              :join-code="course.joinCode"
              @join-code-updated="updateJoinCode"
            />
          </el-tab-pane>
          <el-tab-pane label="公告" name="announcements">
            <CourseAnnouncementManager v-if="activeTab === 'announcements'" :course-id="course.id" />
          </el-tab-pane>
        </el-tabs>
      </section>
    </AsyncState>
  </div>
</template>

<style scoped>
.course-page {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.course-workspace {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 20px;
}

.course-tabs {
  flex: 1;
}

.course-tabs :deep(.el-tabs__content) {
  flex: 1;
}

/* 左侧竖排导航:栏目多,竖排比横排一行挤 12 个更清晰 */
.course-tabs :deep(.el-tabs__header.is-left) {
  margin-right: 24px;
  min-width: 132px;
}

.course-tabs :deep(.el-tabs__item.is-left) {
  justify-content: flex-start;
  height: 40px;
  padding: 0 16px;
  text-align: left;
}

.course-tabs :deep(.el-tabs__content) {
  min-width: 0;
}
</style>
