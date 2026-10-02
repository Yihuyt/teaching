<script setup lang="ts">
import { ref, watch } from 'vue'
import { ArrowLeft } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'
import type { TutorAssistantCard, TutorMountView } from '@/api/generated'
import AsyncState from '@/shared/components/AsyncState.vue'
import TutorChatPanel from '@/features/tutor/components/TutorChatPanel.vue'
import { createLatestRequestGuard } from '@/shared/latestRequest'

const props = defineProps<{ courseId: number }>()

const loading = ref(true)
const loadError = ref('')
const assistants = ref<TutorAssistantCard[]>([])
const active = ref<TutorAssistantCard | null>(null)
const loadRequests = createLatestRequestGuard(() => props.courseId)

async function load(): Promise<void> {
  const request = loadRequests.begin()
  loading.value = true
  loadError.value = ''
  active.value = null
  try {
    const response = await api.tutorListLearnableAssistants(request.snapshot)
    if (!loadRequests.isCurrent(request)) return
    assistants.value = response.data
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) loading.value = false
  }
}

function mountNames(mounts: TutorMountView[]): string {
  return mounts.map((m) => (m.ready ? m.name : `${m.name}(未就绪)`)).join('、')
}

watch(() => props.courseId, load, { immediate: true })
</script>

<template>
  <div v-if="active" class="assistant-chat">
    <div class="assistant-chat__bar">
      <el-button link :icon="ArrowLeft" @click="active = null">全部助手</el-button>
      <strong>{{ active.name }}</strong>
    </div>
    <TutorChatPanel :course-id="courseId" :assistant="active" />
  </div>
  <AsyncState
    v-else
    :loading="loading"
    :error="loadError"
    :empty="assistants.length === 0"
    empty-text="教师尚未配置课程助手"
    @retry="load"
  >
    <ul class="assistant-list">
      <li
        v-for="assistant in assistants"
        :key="assistant.id"
        class="assistant-card"
        @click="active = assistant"
      >
        <div class="assistant-card__main">
          <strong>{{ assistant.name }}</strong>
          <p v-if="assistant.description" class="assistant-card__desc">{{ assistant.description }}</p>
          <p class="assistant-card__mounts">
            <span v-if="assistant.knowledgeBases.length"
              >知识库:{{ mountNames(assistant.knowledgeBases) }}</span
            >
            <span v-if="!assistant.knowledgeBases.length">未挂载知识库</span>
          </p>
        </div>
        <el-button type="primary">开始对话</el-button>
      </li>
    </ul>
  </AsyncState>
</template>

<style scoped>
.assistant-chat {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.assistant-chat__bar {
  display: flex;
  align-items: center;
  gap: 10px;
}


.assistant-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.assistant-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  cursor: pointer;
}

.assistant-card:hover {
  border-color: var(--el-color-primary-light-5);
}

.assistant-card__main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.assistant-card__desc {
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.assistant-card__mounts {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
