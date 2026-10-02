<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
import { Close, UploadFilled } from '@element-plus/icons-vue'

import { errorMessage } from '@/api/client'
import LibraryTable from '@/features/courses/components/LibraryTable.vue'
import { useLibraryBrowser } from '@/features/courses/useLibraryBrowser'
import { contentKey } from '@/features/courses/courseOutline'
import { formatFileSize } from '@/shared/format'
import { rowContent, type LibraryContent, type LibraryRow } from '@/features/courses/library'
import { uploadCourseMaterial, validateUploadFile } from '@/features/courses/materialUpload'

const props = defineProps<{ courseId: number; busy: boolean }>()

const emit = defineEmits<{ add: [items: LibraryContent[]] }>()
const visible = defineModel<boolean>({ required: true })

const { crumbs, keyword, loading, error, rows, parentId, load, enterFolder, goToCrumb, reset } =
  useLibraryBrowser(toRef(props, 'courseId'))

const tab = ref<'library' | 'local'>('library')
const uploadRef = ref<UploadInstance>()
const uploading = ref(false)
const localFiles = ref<File[]>([])
const picked = ref(new Map<string, LibraryContent>())
const pickedCount = computed(() => picked.value.size + localFiles.value.length)

function keyOf(content: LibraryContent): string {
  return contentKey(content.itemType, content.contentId)
}

function isChecked(row: LibraryRow): boolean {
  const content = rowContent(row)
  return content !== null && picked.value.has(keyOf(content))
}

function isDisabled(row: LibraryRow): boolean {
  return rowContent(row) === null
}

function toggle(row: LibraryRow): void {
  const content = rowContent(row)
  if (!content) return
  const next = new Map(picked.value)
  const key = keyOf(content)
  if (next.has(key)) next.delete(key)
  else next.set(key, content)
  picked.value = next
}

function addLocalFile(uploadFile: UploadFile): void {
  const file = uploadFile.raw
  uploadRef.value?.clearFiles()
  if (!file) return
  const invalid = validateUploadFile(file)
  if (invalid) {
    ElMessage.error(invalid)
    return
  }
  if (localFiles.value.some((item) => item.name === file.name && item.size === file.size)) {
    ElMessage.info(`“${file.name}”已在列表中`)
    return
  }
  localFiles.value = [...localFiles.value, file]
}

function removeLocalFile(index: number): void {
  localFiles.value = localFiles.value.filter((_, i) => i !== index)
}

/**
 * 资料库选中项直接加入;本地文件依次上传(同名自动改名)后加入。全部成功后关闭;
 * 上传中途失败则资料库选中项与已传成功的文件先加入,对话框只保留未传的文件供重试。切换课程后丢弃在途结果。
 */
async function submit(): Promise<void> {
  const course = props.courseId
  const folder = parentId.value
  const fromLibrary = [...picked.value.values()]
  if (localFiles.value.length === 0) {
    emit('add', fromLibrary)
    visible.value = false
    return
  }
  uploading.value = true
  const uploaded: LibraryContent[] = []
  try {
    await localFiles.value.reduce(
      (chain, file) =>
        chain.then(async () => {
          if (props.courseId !== course) return
          const { materialId, name } = await uploadCourseMaterial(course, file, folder)
          uploaded.push({ itemType: 'material', contentId: materialId, title: name })
        }),
      Promise.resolve(),
    )
    if (props.courseId !== course) return
    localFiles.value = []
    emit('add', [...fromLibrary, ...uploaded])
    visible.value = false
  } catch (cause: unknown) {
    if (props.courseId !== course) return
    localFiles.value = localFiles.value.slice(uploaded.length)
    picked.value = new Map()
    ElMessage.error(errorMessage(cause))
    const added = [...fromLibrary, ...uploaded]
    if (added.length > 0) emit('add', added)
  } finally {
    uploading.value = false
  }
}

watch(visible, (open) => {
  if (!open) return
  tab.value = 'library'
  picked.value = new Map()
  localFiles.value = []
  reset()
})
</script>

<template>
  <el-dialog v-model="visible" title="添加内容" width="920px" destroy-on-close>
    <el-tabs v-model="tab">
      <el-tab-pane label="资料库" name="library">
        <LibraryTable
          v-model:keyword="keyword"
          selectable
          :rows="rows"
          :crumbs="crumbs"
          :loading="loading"
          :error="error"
          :is-checked="isChecked"
          :is-disabled="isDisabled"
          @enter-folder="enterFolder"
          @go-to-crumb="goToCrumb"
          @retry="load"
          @toggle="toggle"
        />
      </el-tab-pane>
      <el-tab-pane label="本地上传" name="local">
        <el-upload
          ref="uploadRef"
          drag
          multiple
          :auto-upload="false"
          :show-file-list="false"
          :on-change="addLocalFile"
          class="local-upload"
        >
          <el-icon class="local-upload__icon"><UploadFilled /></el-icon>
          <div>把文件拖到这里，或点击选择</div>
          <div class="local-upload__target">上传到 {{ crumbs.map((crumb) => crumb.name).join(' / ') }}</div>
        </el-upload>
        <ul v-if="localFiles.length" class="local-list">
          <li v-for="(file, index) in localFiles" :key="`${file.name}-${file.size}`">
            <span class="local-name">{{ file.name }}</span>
            <span class="local-size">{{ formatFileSize(file.size) }}</span>
            <el-button
              link
              size="small"
              :icon="Close"
              :disabled="uploading"
              :aria-label="`移除 ${file.name}`"
              @click="removeLocalFile(index)"
            />
          </li>
        </ul>
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <span class="picked-count">已选 {{ pickedCount }} 项</span>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="pickedCount === 0 || busy" :loading="uploading" @click="submit">
        {{ uploading ? '正在上传…' : '添加' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.local-upload {
  width: 100%;
}

.local-upload :deep(.el-upload-dragger) {
  padding: 36px 0;
}

.local-upload__target {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.local-upload__icon {
  margin-bottom: 8px;
  color: var(--el-text-color-placeholder);
  font-size: 40px;
}

.local-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.local-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.local-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.local-size {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.picked-count {
  margin-right: 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
