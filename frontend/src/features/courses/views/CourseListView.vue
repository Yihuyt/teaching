<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'
import type { CourseView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import { plainTextExcerpt } from '@/shared/markdown'

const loading = ref(true)
const loadError = ref('')
const items = ref<CourseView[]>([])
const total = ref(0)
const joinCode = ref('')
const joining = ref(false)
const query = reactive({
  page: 1,
  size: 12,
  keyword: '',
})

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.courseList(query)
    items.value = response.data.items
    total.value = response.data.total
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function search(): void {
  query.page = 1
  void load()
}

async function joinCourse(): Promise<void> {
  const normalizedCode = joinCode.value.trim().toUpperCase()
  if (!/^[0-9A-F]{10}$/.test(normalizedCode)) {
    ElMessage.error('请输入教师提供的 10 位课程码')
    return
  }
  joining.value = true
  try {
    const response = await api.courseJoin({ joinCode: normalizedCode })
    joinCode.value = ''
    query.page = 1
    query.keyword = ''
    await load()
    ElMessage.success(`已加入课程“${response.data.title}”`)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    joining.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <PageHeader title="我的课程" />
    <section class="join-panel">
      <div class="join-panel__intro">
        <h2>加入课程</h2>
      </div>
      <div class="join-panel__form">
        <el-input
          v-model="joinCode"
          maxlength="10"
          placeholder="请输入 10 位课程码"
          class="join-code-input"
          @keyup.enter="joinCourse"
        />
        <el-button type="primary" :loading="joining" @click="joinCourse">加入课程</el-button>
      </div>
    </section>
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        clearable
        placeholder="搜索课程名称"
        style="width: 360px"
        @keyup.enter="search"
        @clear="search"
      >
        <template #append>
          <el-button :icon="Search" aria-label="搜索课程" @click="search" />
        </template>
      </el-input>
      <span class="muted">共 {{ total }} 门课程</span>
    </div>
    <AsyncState
      :loading="loading"
      :error="loadError"
      :empty="items.length === 0"
      empty-text="没有符合条件的课程"
      @retry="load"
    >
      <div class="course-grid">
        <router-link
          v-for="course in items"
          :key="course.id"
          :to="`/courses/${course.id}`"
          class="course-card"
        >
          <div class="course-card__body">
            <h2>{{ course.title }}</h2>
            <p v-if="plainTextExcerpt(course.descriptionMarkdown, 100)">
              {{ plainTextExcerpt(course.descriptionMarkdown, 100) }}
            </p>
            <span class="course-card__action">进入课程</span>
          </div>
        </router-link>
      </div>
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[12, 24, 48]"
        layout="total, sizes, prev, pager, next"
        class="pagination"
        @current-change="load"
        @size-change="search"
      />
    </AsyncState>
  </div>
</template>

<style scoped>
.join-panel {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  margin-bottom: 20px;
  padding: 18px 20px;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  background: #fff;
}

.join-panel__intro {
  flex: 1;
}

.join-panel h2,
.join-panel p {
  margin: 0;
}

.join-panel h2 {
  margin-bottom: 4px;
  font-size: 17px;
}

.join-panel p {
  color: var(--text-secondary);
}

.join-panel__form {
  display: flex;
  gap: 10px;
  width: 410px;
}

.join-code-input {
  flex: 1;
}

.join-code-input :deep(.el-input__inner) {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.course-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

.course-card {
  display: flex;
  border: 1px solid var(--border);
  border-radius: var(--radius-panel);
  color: var(--text);
  background: #fff;
  transition: border-color 160ms ease;
}

.course-card:hover {
  border-color: #98a2b3;
}

.course-card__body {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 160px;
  padding: 22px;
}

h2 {
  margin: 0;
  font-size: 18px;
}

.course-card p {
  display: -webkit-box;
  overflow: hidden;
  margin: 14px 0 20px;
  color: var(--text-secondary);
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.course-card__action {
  margin-top: auto;
  color: var(--brand);
  font-size: 14px;
}

.pagination {
  justify-content: flex-end;
  margin-top: 24px;
}
</style>
