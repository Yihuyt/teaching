<script setup lang="ts">
import { ref, toRef, watch } from 'vue'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'

import { errorMessage } from '@/api/client'
import LibraryTable from '@/features/courses/components/LibraryTable.vue'
import { useLibraryBrowser } from '@/features/courses/useLibraryBrowser'
import { uploadCourseMaterial, validateUploadFile } from '@/features/courses/materialUpload'
import { resourceKey } from '@/features/knowledgegraph/knowledgeGraph'
import { rowContent, type LibraryContent, type LibraryRow } from '@/features/courses/library'

const props = defineProps<{ courseId: number; busy: boolean; attached: Set<string> }>()

const emit = defineEmits<{ pick: [content: LibraryContent] }>()
const visible = defineModel<boolean>({ required: true })

const { crumbs, keyword, loading, error, rows, parentId, load, enterFolder, goToCrumb, reset } =
  useLibraryBrowser(toRef(props, 'courseId'))

function isDisabled(row: LibraryRow): boolean {
  const content = rowContent(row)
  return content === null || props.attached.has(resourceKey(content.itemType, content.contentId))
}

function toggle(row: LibraryRow): void {
  const content = rowContent(row)
  if (content) emit('pick', content)
}

const uploading = ref(false)
const uploadRef = ref<UploadInstance>()

async function uploadOnSelect(uploadFile: UploadFile): Promise<void> {
  const file = uploadFile.raw
  if (!file) return
  const invalid = validateUploadFile(file)
  if (invalid) {
    uploadRef.value?.clearFiles()
    ElMessage.error(invalid)
    return
  }
  uploading.value = true
  try {
    const { materialId, name } = await uploadCourseMaterial(props.courseId, file, parentId.value)
    await load()
    ElMessage.success(`“${name}”已上传`)
    emit('pick', { itemType: 'material', contentId: materialId, title: name })
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  } finally {
    uploading.value = false
    uploadRef.value?.clearFiles()
  }
}

watch(visible, (open) => {
  if (open) reset()
})
</script>

<template>
  <el-dialog v-model="visible" title="挂载资源" width="920px" destroy-on-close append-to-body>
    <div class="toolbar">
      <el-upload
        ref="uploadRef"
        :auto-upload="false"
        :show-file-list="false"
        :disabled="busy || uploading"
        :on-change="uploadOnSelect"
      >
        <el-button :icon="UploadFilled" :loading="uploading" :disabled="busy">
          {{ uploading ? '正在上传…' : '上传文件并挂载' }}
        </el-button>
      </el-upload>
    </div>
    <LibraryTable
      v-model:keyword="keyword"
      selectable
      :rows="rows"
      :crumbs="crumbs"
      :loading="loading"
      :error="error"
      :is-checked="() => false"
      :is-disabled="(row: LibraryRow) => busy || isDisabled(row)"
      @enter-folder="enterFolder"
      @go-to-crumb="goToCrumb"
      @retry="load"
      @toggle="toggle"
    />
    <template #footer>
      <el-button @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-bottom: 10px;
}

</style>
