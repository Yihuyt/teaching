<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api, errorMessage } from '@/api/client'
import type { CoursewareImageView, CoursewareMaterialView } from '@/api/generated'
import { IMAGE_ASPECT_RATIOS, IMAGE_ASPECT_RATIO_LABELS, LIMITS } from '@/features/courseware/dsl'
import type { ImageAspectRatio } from '@/features/courseware/dsl'

const props = defineProps<{
  visible: boolean
  courseId: number
  coursewareId: number
  replacing?: boolean
}>()
const emit = defineEmits<{ 'update:visible': [value: boolean]; picked: [image: CoursewareImageView] }>()

const tab = ref<'upload' | 'material' | 'generate'>('upload')
const prompt = ref('')
const aspectRatio = ref<ImageAspectRatio>('16:9')
const working = ref(false)
const materials = ref<CoursewareMaterialView[]>([])
const materialsLoaded = ref(false)

watch(
  () => props.visible,
  (visible) => {
    if (!visible) return
    tab.value = 'upload'
    prompt.value = ''
    working.value = false
    materialsLoaded.value = false
    void loadMaterials()
  },
)

async function loadMaterials(): Promise<void> {
  try {
    const response = await api.coursewareMaterialList(props.courseId, props.coursewareId)
    materials.value = response.data.filter((m) => m.images.length > 0)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    materialsLoaded.value = true
  }
}

function close(): void {
  emit('update:visible', false)
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  working.value = true
  try {
    const response = await api.coursewareImageUpload(props.courseId, props.coursewareId, { file })
    emit('picked', response.data)
    close()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    working.value = false
  }
}

async function generate(): Promise<void> {
  if (working.value) return
  if (!prompt.value.trim()) {
    ElMessage.warning('请描述要生成的图')
    return
  }
  working.value = true
  try {
    const response = await api.coursewareImageGenerate(props.courseId, props.coursewareId, {
      prompt: prompt.value.trim(),
      aspectRatio: aspectRatio.value,
    })
    emit('picked', response.data)
    close()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    working.value = false
  }
}

async function pick(materialId: number, imageId: string): Promise<void> {
  if (working.value) return
  working.value = true
  try {
    const response = await api.coursewareImagePickFromMaterial(props.courseId, props.coursewareId, { materialId, imageId })
    emit('picked', response.data)
    close()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    working.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="visible" :title="replacing ? '换图' : '插入图片'" width="640px" @update:model-value="close">
    <el-tabs v-model="tab">
      <el-tab-pane label="上传图片" name="upload">
        <label class="upload-box" :class="{ disabled: working }">
          <input type="file" accept="image/png,image/jpeg,image/gif" :disabled="working" @change="onFileChange" />
          <span v-if="working">上传中…</span>
          <span v-else>点击选择图片(PNG / JPEG / GIF,不超过 10MB)</span>
        </label>
      </el-tab-pane>
      <el-tab-pane label="素材里的图" name="material">
        <p v-if="!materialsLoaded" class="hint">加载素材…</p>
        <p v-else-if="materials.length === 0" class="hint">素材里没有图片</p>
        <div v-for="m in materials" v-else :key="m.id" class="material-group">
          <div class="material-name">{{ m.name }}</div>
          <div class="thumb-grid">
            <button
              v-for="img in m.images"
              :key="img.id"
              type="button"
              class="thumb"
              :disabled="working"
              :title="img.description || img.sourceDocumentName"
              @click="pick(m.id, img.id)"
            >
              <img :src="img.url" :alt="img.description" />
              <span class="thumb-meta">{{ img.width }}×{{ img.height }}</span>
            </button>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="AI 生成" name="generate">
        <el-input
          v-model="prompt"
          type="textarea"
          :rows="3"
          :maxlength="LIMITS.imagePromptChars"
          resize="none"
          :disabled="working"
          placeholder="描述画面内容,比如“光线在平面镜上反射的示意图,标出入射角与反射角”"
        />
        <div class="generate-row">
          <el-select v-model="aspectRatio" class="ratio-select" :disabled="working">
            <el-option v-for="ratio in IMAGE_ASPECT_RATIOS" :key="ratio" :label="IMAGE_ASPECT_RATIO_LABELS[ratio]" :value="ratio" />
          </el-select>
          <el-button type="primary" :loading="working" @click="generate">{{ working ? '生成中…' : '生成' }}</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<style scoped>
.upload-box {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 140px;
  border: 1px dashed var(--border);
  border-radius: var(--radius-panel);
  color: var(--text-muted);
  cursor: pointer;
}

.upload-box:hover {
  border-color: var(--brand);
  color: var(--brand);
}

.upload-box.disabled {
  cursor: default;
}

.upload-box input {
  display: none;
}

.generate-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.ratio-select {
  width: 130px;
}

.hint {
  margin: 8px 0;
  font-size: 12.5px;
  color: var(--text-muted);
}

.material-group {
  margin-bottom: 12px;
}

.material-name {
  margin-bottom: 6px;
  font-size: 12.5px;
  color: var(--text-muted);
}

.thumb-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.thumb {
  position: relative;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 4px;
  background: #fff;
  overflow: hidden;
  cursor: pointer;
  aspect-ratio: 4 / 3;
}

.thumb:hover {
  border-color: var(--brand);
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.thumb-meta {
  position: absolute;
  right: 4px;
  bottom: 4px;
  padding: 0 4px;
  border-radius: 3px;
  background: rgba(31, 35, 41, 0.7);
  color: #fff;
  font-size: 11px;
}
</style>
