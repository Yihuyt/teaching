<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { api, errorMessage } from '@/api/client'
import AsyncState from '@/shared/components/AsyncState.vue'
import BlockCodingProjectList from '@/features/blockcoding/components/BlockCodingProjectList.vue'
import { createLatestRequestGuard } from '@/shared/latestRequest'

const props = defineProps<{ courseId: number }>()

const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const form = reactive({
  enabled: false,
  tutorPrompt: '',
  model: '',
})
const models = ref<string[]>([])

const loadRequests = createLatestRequestGuard(() => props.courseId)

async function load(): Promise<void> {
  const request = loadRequests.begin()
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.courseBlockCodingConfigGetForManagement(request.snapshot)
    if (!loadRequests.isCurrent(request)) return
    form.enabled = response.data.enabled
    form.tutorPrompt = response.data.tutorPrompt
    form.model = response.data.model
    models.value = response.data.models
  } catch (error: unknown) {
    if (!loadRequests.isCurrent(request)) return
    loadError.value = errorMessage(error)
  } finally {
    if (loadRequests.isCurrent(request)) {
      loading.value = false
    }
  }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    const response = await api.courseBlockCodingConfigUpdate(props.courseId, {
      enabled: form.enabled,
      tutorPrompt: form.tutorPrompt,
      model: form.model,
    })
    form.enabled = response.data.enabled
    form.tutorPrompt = response.data.tutorPrompt
    form.model = response.data.model
    ElMessage.success('积木编程辅导配置已保存')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    saving.value = false
  }
}

watch(
  () => props.courseId,
  () => void load(),
  { immediate: true },
)
</script>

<template>
  <AsyncState :loading="loading" :error="loadError" :empty="false" @retry="load">
    <div class="blockcoding-config">
      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="向学生开放">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="模型">
          <el-select v-model="form.model" class="model-select">
            <el-option v-for="m in models" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="辅导提示词">
          <el-input
            v-model="form.tutorPrompt"
            type="textarea"
            :rows="8"
            maxlength="4000"
            show-word-limit
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </el-form-item>
      </el-form>
      <h3 class="section-title">我的作品</h3>
      <BlockCodingProjectList :course-id="props.courseId" management />
    </div>
  </AsyncState>
</template>

<style scoped>
.blockcoding-config {
  max-width: 960px;
}

.section-title {
  margin: 28px 0 12px;
  font-size: 15px;
}

.model-select {
  width: 240px;
}

</style>
