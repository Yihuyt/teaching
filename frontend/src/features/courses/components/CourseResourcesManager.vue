<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
import { Delete, Download, Edit, FolderAdd, Plus, UploadFilled } from '@element-plus/icons-vue'

import { api, errorMessage } from '@/api/client'
import type { CourseProgrammingProblemView, CourseQuestionView, MaterialView } from '@/api/generated'
import LibraryTable from '@/features/courses/components/LibraryTable.vue'
import { useLibraryBrowser } from '@/features/courses/useLibraryBrowser'
import { deletionPrompt } from '@/shared/deletion'
import { libraryRowKey, type LibraryRow } from '@/features/courses/library'
import { openInNewTab } from '@/shared/openUrl'
import { confirm, prompt } from '@/shared/dialogs'
import { uploadCourseMaterial, validateMaterialName, validateUploadFile } from '@/features/courses/materialUpload'

const props = defineProps<{ courseId: number }>()

const router = useRouter()
const browser = useLibraryBrowser(toRef(props, 'courseId'))
const { crumbs, keyword, loading, error, rows, parentId, load, enterFolder, goToCrumb, reset } = browser

const uploading = ref(false)
const uploadRef = ref<UploadInstance>()

async function createFolder(): Promise<void> {
  const name = await prompt('文件夹名称', '新建文件夹', { validate: validateMaterialName })
  if (name === null) return
  try {
    await api.courseMaterialCreateFolder(props.courseId, { parentId: parentId.value, name: name.trim() })
    await load()
    ElMessage.success('文件夹已创建')
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  }
}

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
    const { name } = await uploadCourseMaterial(props.courseId, file, parentId.value)
    await load()
    ElMessage.success(`“${name}”已上传`)
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  } finally {
    uploading.value = false
    uploadRef.value?.clearFiles()
  }
}

async function download(material: MaterialView): Promise<void> {
  try {
    await openInNewTab(
      async () =>
        (await api.courseMaterialCreateDownloadTicketForManagement(props.courseId, material.id)).data.url,
    )
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  }
}

async function renameMaterial(material: MaterialView): Promise<void> {
  const name = await prompt('新名称', '重命名', { value: material.name, validate: validateMaterialName })
  if (name === null) return
  try {
    await api.courseMaterialUpdateMetadata(props.courseId, material.id, { name: name.trim() })
    await load()
    ElMessage.success('已重命名')
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  }
}

const selected = ref(new Set<string>())
const selectedRows = computed(() => rows.value.filter((row) => selected.value.has(libraryRowKey(row))))

function toggle(row: LibraryRow): void {
  const key = libraryRowKey(row)
  const next = new Set(selected.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  selected.value = next
}

interface Selection {
  materialIds: number[]
  questionIds: number[]
  problemIds: number[]
}

function selectionOf(list: LibraryRow[]): Selection {
  const selection: Selection = { materialIds: [], questionIds: [], problemIds: [] }
  for (const row of list) {
    if (row.kind === 'folder' || row.kind === 'file') selection.materialIds.push(row.material.id)
    else if (row.kind === 'question') selection.questionIds.push(row.question.id)
    else selection.problemIds.push(row.problem.id)
  }
  return selection
}

/**
 * 删除一律级联(从课程内容移除、图谱节点卸载、连带作答 / 提交、文件夹连带内容),
 * 确认框只区分有 / 无关联,不罗列明细。
 */
async function removeRows(list: LibraryRow[]): Promise<void> {
  if (list.length === 0) return
  const selection = selectionOf(list)
  try {
    const { associated } = (await api.courseLibraryDeletionImpact(props.courseId, selection)).data
    if (!(await confirm(deletionPrompt(list.length, associated), '删除'))) return
    if (selection.materialIds.length) {
      await api.courseMaterialDeleteMany(props.courseId, { ids: selection.materialIds })
    }
    if (selection.questionIds.length) {
      await api.courseQuestionDeleteMany(props.courseId, { ids: selection.questionIds })
    }
    if (selection.problemIds.length) {
      await api.courseProgrammingProblemDeleteMany(props.courseId, { ids: selection.problemIds })
    }
    selected.value = new Set()
    await load()
    ElMessage.success('已删除')
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  }
}

function openQuestion(question?: CourseQuestionView): void {
  void router.push(
    question
      ? `/focus/admin/courses/${props.courseId}/questions/${question.id}/edit`
      : `/focus/admin/courses/${props.courseId}/questions/new`,
  )
}

function openProblem(problem?: CourseProgrammingProblemView): void {
  void router.push(
    problem
      ? `/focus/admin/courses/${props.courseId}/programming-problems/${problem.id}/edit`
      : `/focus/admin/courses/${props.courseId}/programming-problems/new`,
  )
}

watch(
  () => props.courseId,
  () => {
    selected.value = new Set()
    reset()
  },
  { immediate: true },
)
watch(rows, () => {
  const keys = new Set(rows.value.map(libraryRowKey))
  selected.value = new Set([...selected.value].filter((key) => keys.has(key)))
})
</script>

<template>
  <section>
    <div class="toolbar-actions">
      <el-button
        type="danger"
        plain
        :icon="Delete"
        :disabled="selectedRows.length === 0"
        @click="removeRows(selectedRows)"
      >
        删除所选
      </el-button>
      <el-button :icon="FolderAdd" @click="createFolder">新建文件夹</el-button>
      <el-upload
        ref="uploadRef"
        :auto-upload="false"
        :limit="1"
        :show-file-list="false"
        :on-change="uploadOnSelect"
      >
        <el-button type="primary" :loading="uploading" :icon="UploadFilled">
          {{ uploading ? '正在上传…' : '上传文件' }}
        </el-button>
      </el-upload>
      <el-button :icon="Plus" @click="openQuestion()">新建试题</el-button>
      <el-button :icon="Plus" @click="openProblem()">新建编程题</el-button>
    </div>

    <LibraryTable
      v-model:keyword="keyword"
      :rows="rows"
      :crumbs="crumbs"
      :loading="loading"
      :error="error"
      selectable
      folder-selectable
      :is-checked="(row) => selected.has(libraryRowKey(row))"
      @toggle="toggle"
      @enter-folder="enterFolder"
      @go-to-crumb="goToCrumb"
      @retry="load"
    >
      <template #actions="{ row }">
        <template v-if="row.kind === 'folder'">
          <el-button link :icon="Edit" @click="renameMaterial(row.material)">重命名</el-button>
          <el-button link type="danger" :icon="Delete" @click="removeRows([row])">删除</el-button>
        </template>
        <template v-else-if="row.kind === 'file'">
          <el-button link :icon="Download" @click="download(row.material)">下载</el-button>
          <el-button link :icon="Edit" @click="renameMaterial(row.material)">重命名</el-button>
          <el-button link type="danger" :icon="Delete" @click="removeRows([row])">删除</el-button>
        </template>
        <template v-else-if="row.kind === 'question'">
          <el-button link :icon="Edit" @click="openQuestion(row.question)">编辑</el-button>
          <el-button link type="danger" :icon="Delete" @click="removeRows([row])">删除</el-button>
        </template>
        <template v-else>
          <el-button link :icon="Edit" @click="openProblem(row.problem)">编辑</el-button>
          <el-button link type="danger" :icon="Delete" @click="removeRows([row])">删除</el-button>
        </template>
      </template>
    </LibraryTable>
  </section>
</template>

<style scoped>
.toolbar-actions {
  display: flex;
  align-items: flex-start;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
  margin-bottom: 14px;
}

.toolbar-actions .el-button + .el-button {
  margin-left: 0;
}
</style>
