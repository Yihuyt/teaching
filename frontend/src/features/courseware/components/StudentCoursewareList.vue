<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { api, errorMessage } from '@/api/client'
import type { CoursewareSummary } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import { formatEpochMillis } from '@/shared/format'
import { createLatestRequestGuard } from '@/shared/latestRequest'

const props = defineProps<{ courseId: number }>()
const router = useRouter()

const loading = ref(true)
const loadError = ref('')
const items = ref<CoursewareSummary[]>([])
const loadRequests = createLatestRequestGuard(() => props.courseId)

async function load(): Promise<void> {
  const request = loadRequests.begin()
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.coursewareListLearnable(request.snapshot)
    if (!loadRequests.isCurrent(request)) return
    items.value = response.data
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) {
      loading.value = false
    }
  }
}

watch(() => props.courseId, load, { immediate: true })

function play(item: CoursewareSummary): void {
  void router.push(`/focus/courses/${props.courseId}/coursewares/${item.id}/play`)
}
</script>

<template>
  <AsyncState
    :loading="loading"
    :error="loadError"
    :empty="items.length === 0"
    empty-text="本课程还没有可学习的课件"
    @retry="load"
  >
    <ul class="courseware-list">
      <li v-for="item in items" :key="item.id" class="courseware-item">
        <div class="courseware-item__main">
          <strong>{{ item.title }}</strong>
          <span class="courseware-item__meta"
            >{{ item.sceneCount }} 页 · 更新于
            {{ formatEpochMillis(item.updatedAt) }}</span
          >
        </div>
        <el-button type="primary" @click="play(item)">开始学习</el-button>
      </li>
    </ul>
  </AsyncState>
</template>

<style scoped>
.courseware-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.courseware-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.courseware-item__main {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.courseware-item__meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
