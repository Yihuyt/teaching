<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'
import { CourseProgrammingProblemViewDifficulty } from '@/api/generated'
import { difficultyLabels } from '@/shared/labels'
import { validateProblemPackageFile } from '@/features/programming/problemPackage'

const props = defineProps<{ courseId: number }>()
const emit = defineEmits<{ imported: [problemIds: number[]] }>()

const visible = ref(false)
const files = ref<File[]>([])
const pendingImported = ref<number[]>([])
const difficulty = ref<CourseProgrammingProblemViewDifficulty>(CourseProgrammingProblemViewDifficulty.easy)
const importing = ref(false)
const error = ref('')
const progress = ref({ done: 0, total: 0 })
const failures = ref<{ name: string; message: string }[]>([])
const uploadRef = ref<UploadInstance>()

function open(): void {
  files.value = []
  error.value = ''
  failures.value = []
  progress.value = { done: 0, total: 0 }
  uploadRef.value?.clearFiles()
  visible.value = true
}

function select(_: UploadFile, list: UploadFile[]): void {
  const selected: File[] = []
  for (const item of list) {
    if (item.raw) selected.push(item.raw)
  }
  for (const file of selected) {
    const problem = validateProblemPackageFile(file)
    if (problem) {
      error.value = problem
      uploadRef.value?.clearFiles()
      files.value = []
      return
    }
  }
  error.value = ''
  files.value = selected
}

async function submit(): Promise<void> {
  const selected = files.value
  if (selected.length === 0) {
    error.value = '请选择题目包'
    return
  }
  importing.value = true
  error.value = ''
  failures.value = []
  progress.value = { done: 0, total: selected.length }
  const imported: number[] = []
  await selected.reduce(
    (previous, file) =>
      previous.then(async () => {
        try {
          const { data } = await api.courseProgrammingProblemImportPackage(
            props.courseId,
            { difficulty: difficulty.value },
            { file },
          )
          imported.push(data.details.problem.id)
        } catch (cause: unknown) {
          failures.value.push({ name: file.name, message: errorMessage(cause) })
        }
        progress.value.done += 1
      }),
    Promise.resolve(),
  )
  importing.value = false
  if (failures.value.length === 0) {
    visible.value = false
    ElMessage.success(selected.length === 1 ? '题目包已导入' : `${imported.length} 个题目包已导入`)
    emit('imported', imported)
    return
  }
  uploadRef.value?.clearFiles()
  files.value = []
  ElMessage.warning(`${imported.length} 个导入成功，${failures.value.length} 个失败`)
  // 成功的那些已在资料库里;对话框留着失败原因,等用户看完关闭再由父组件刷新
  pendingImported.value = imported
}

/** 部分失败时关闭对话框才通知父组件(否则父组件跳转会把失败原因一起关掉) */
watch(visible, (shown) => {
  if (shown || pendingImported.value.length === 0) return
  const imported = pendingImported.value
  pendingImported.value = []
  emit('imported', imported)
})

defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" title="导入题目包" width="640px">
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="dialog-alert" />
    <el-form label-position="top">
      <el-form-item label="难度">
        <el-select v-model="difficulty">
          <el-option v-for="(label, value) in difficultyLabels" :key="value" :label="label" :value="value" />
        </el-select>
      </el-form-item>
      <el-form-item label="题目包">
        <el-upload
          ref="uploadRef"
          :auto-upload="false"
          multiple
          accept=".zip,.kpp,application/zip"
          :on-change="select"
          :on-remove="select"
          drag
          class="full-width"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖入题目包（.zip / .kpp），或点击选择</div>
        </el-upload>
      </el-form-item>
      <el-progress
        v-if="progress.total > 0"
        :percentage="Math.round((progress.done / progress.total) * 100)"
        :format="() => `${progress.done} / ${progress.total}`"
      />
      <el-table v-if="failures.length" :data="failures" size="small" max-height="240">
        <el-table-column prop="name" label="文件" width="220" show-overflow-tooltip />
        <el-table-column prop="message" label="失败原因" show-overflow-tooltip />
      </el-table>
    </el-form>
    <template #footer>
      <el-button :disabled="importing" @click="visible = false">关闭</el-button>
      <el-button type="primary" :disabled="files.length === 0" :loading="importing" @click="submit">
        上传并校验
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dialog-alert {
  margin-bottom: 12px;
}

.full-width {
  width: 100%;
}

.full-width :deep(.el-upload),
.full-width :deep(.el-upload-dragger) {
  width: 100%;
}
</style>
