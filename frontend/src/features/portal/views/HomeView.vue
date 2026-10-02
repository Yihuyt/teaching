<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { api, errorMessage } from '@/api/client'
import type { AnnouncementView, CourseView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'
import { formatDateTime } from '@/shared/format'
import { plainTextExcerpt } from '@/shared/markdown'

const router = useRouter()
const loading = ref(true)
const loadError = ref('')
const courses = ref<CourseView[]>([])
const announcements = ref<AnnouncementView[]>([])
const selectedAnnouncement = ref<AnnouncementView>()
const announcementDialogVisible = ref(false)

function openAnnouncement(announcement: AnnouncementView): void {
  selectedAnnouncement.value = announcement
  announcementDialogVisible.value = true
}

function clearSelectedAnnouncement(): void {
  selectedAnnouncement.value = undefined
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const [courseResponse, announcementResponse] = await Promise.all([
      api.courseList({ page: 1, size: 6 }),
      api.announcementList({ page: 1, size: 5 }),
    ])
    courses.value = courseResponse.data.items
    announcements.value = announcementResponse.data.items
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <AsyncState :loading="loading" :error="loadError" :empty="false" @retry="load">
      <div class="page-grid">
        <section class="panel course-panel">
          <div class="panel-header">
            <h1>我的课程</h1>
            <el-button text type="primary" @click="router.push('/courses')">查看全部</el-button>
          </div>
          <div v-if="courses.length" class="course-list">
            <router-link
              v-for="course in courses"
              :key="course.id"
              :to="`/courses/${course.id}`"
              class="course-item"
            >
              <span class="course-item__content">
                <strong>{{ course.title }}</strong>
                <small v-if="plainTextExcerpt(course.descriptionMarkdown, 80)">
                  {{ plainTextExcerpt(course.descriptionMarkdown, 80) }}
                </small>
              </span>
              <span class="course-item__action">进入课程</span>
            </router-link>
          </div>
          <p v-else class="empty-text">暂无课程</p>
        </section>

        <section class="panel announcement-panel">
          <div class="panel-header">
            <h2>最新公告</h2>
          </div>
          <div class="list-body">
            <article v-for="announcement in announcements" :key="announcement.id" class="announcement">
              <strong>{{ announcement.title }}</strong>
              <p v-if="plainTextExcerpt(announcement.contentMarkdown, 100)">
                {{ plainTextExcerpt(announcement.contentMarkdown, 100) }}
              </p>
              <div class="announcement__footer">
                <time>{{ formatDateTime(announcement.createdAt) }}</time>
                <el-button link type="primary" @click="openAnnouncement(announcement)"> 查看全文 </el-button>
              </div>
            </article>
            <p v-if="!announcements.length" class="empty-text">暂无公告</p>
          </div>
        </section>
      </div>
    </AsyncState>

    <el-dialog
      v-model="announcementDialogVisible"
      :title="selectedAnnouncement?.title ?? '公告'"
      width="720px"
      destroy-on-close
      @closed="clearSelectedAnnouncement"
    >
      <article v-if="selectedAnnouncement" class="announcement-detail">
        <time>{{ formatDateTime(selectedAnnouncement.createdAt) }}</time>
        <MarkdownRenderer :source="selectedAnnouncement.contentMarkdown" />
      </article>
      <template #footer>
        <el-button @click="announcementDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.course-panel {
  grid-column: span 8;
}

.announcement-panel {
  grid-column: span 4;
}

.panel-header h1,
.panel-header h2 {
  margin: 0;
  font-size: 18px;
}

.course-list,
.list-body {
  padding: 8px 20px 18px;
}

.course-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 16px 0;
  border-bottom: 1px solid var(--border);
  color: var(--text);
}

.course-item:last-child,
.announcement:last-child {
  border-bottom: 0;
}

.course-item__content {
  min-width: 0;
}

.course-item__content strong,
.course-item__content small {
  display: block;
}

.course-item__content small {
  margin-top: 6px;
  color: var(--text-muted);
  line-height: 1.5;
}

.course-item__action {
  color: var(--brand);
  font-size: 14px;
}

.announcement {
  padding: 16px 0;
  border-bottom: 1px solid var(--border);
}

.announcement p {
  display: -webkit-box;
  overflow: hidden;
  margin: 7px 0;
  color: var(--text-secondary);
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.announcement__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.announcement time,
.announcement-detail time {
  color: var(--text-muted);
  font-size: 12px;
}

.announcement-detail time {
  display: block;
  margin-bottom: 18px;
}

.empty-text {
  margin: 0;
  padding: 36px 0;
  color: var(--text-muted);
  text-align: center;
}
</style>
