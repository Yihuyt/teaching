<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorMessage } from '@/api/client'

const props = defineProps<{ courseId: number; coursewareId: number; disabled?: boolean }>()
const emit = defineEmits<{ uploaded: [video: { src: string; url: string }] }>()

const MAX_BYTES = 200 * 1024 * 1024
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const previewUrl = ref('')
const fileName = ref('')

function pickFile(): void {
  fileInput.value?.click()
}

async function onFilePicked(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > MAX_BYTES) {
    ElMessage.warning('视频不能超过 200MB')
    return
  }
  uploading.value = true
  try {
    const response = await api.coursewareVideoUpload(props.courseId, props.coursewareId, { file })
    previewUrl.value = response.data.url
    fileName.value = file.name
    emit('uploaded', response.data)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="video-upload">
    <input ref="fileInput" type="file" accept="video/mp4,video/webm,.mp4,.webm" hidden @change="onFilePicked" />
    <div class="upload-row">
      <el-button :loading="uploading" :disabled="disabled" @click="pickFile">
        {{ uploading ? '上传中…' : previewUrl ? '换一个视频' : '选择视频文件' }}
      </el-button>
      <span class="upload-hint">MP4 或 WebM,不超过 200MB{{ fileName ? ' · ' + fileName : '' }}</span>
    </div>
    <video v-if="previewUrl" class="upload-preview" :src="previewUrl" controls preload="metadata" />
  </div>
</template>

<style scoped>
.video-upload {
  width: 100%;
}

.upload-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.upload-hint {
  font-size: 12px;
  color: var(--text-muted);
}

.upload-preview {
  margin-top: 8px;
  width: 100%;
  max-height: 260px;
  background: #000;
  border-radius: var(--radius-panel);
}
</style>
