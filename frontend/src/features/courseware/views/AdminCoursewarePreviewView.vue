<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import { api, errorMessage } from '@/api/client'
import type { Stage } from '@/features/courseware/dsl'
import CoursewarePlayer from '@/features/courseware/components/CoursewarePlayer.vue'

const route = useRoute()
const courseId = computed(() => Number(route.params.courseId))
const coursewareId = computed(() => Number(route.params.coursewareId))

const loading = ref(true)
const loadError = ref('')
const title = ref('')
const stage = ref<Stage | null>(null)
const assetUrls = ref<Record<string, string>>({})

onMounted(async () => {
  try {
    const response = await api.coursewareGet(courseId.value, coursewareId.value)
    title.value = response.data.title
    stage.value = response.data.stage as unknown as Stage
    assetUrls.value = response.data.assetUrls
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" />
  <div v-else-if="loading" class="player-loading">课件加载中…</div>
  <CoursewarePlayer
    v-else-if="stage"
    :course-id="courseId"
    :courseware-id="coursewareId"
    :title="title"
    :stage="stage"
    :asset-urls="assetUrls"
    mode="preview"
  />
</template>

<style scoped>
.player-loading {
  padding: 64px 0;
  text-align: center;
  color: var(--text-muted);
}
</style>
