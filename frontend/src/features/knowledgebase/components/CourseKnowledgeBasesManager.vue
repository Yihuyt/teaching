<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
import { Delete, Edit, FolderOpened, Plus, Refresh, UploadFilled } from '@element-plus/icons-vue'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { KbDocumentView, KnowledgeBaseView, MaterialView } from '@/api/generated'
import {
  streamDocumentEvents,
  streamRebuildEvents,
  type KbIngestEvent,
} from '@/features/knowledgebase/knowledgebaseStream'
import AsyncState from '@/shared/components/AsyncState.vue'
import { formatDateTime } from '@/shared/format'
import { uploadCourseMaterial, validateUploadFile } from '@/features/courses/materialUpload'

const INDEXABLE_SUFFIXES = new Set(['pdf', 'docx', 'pptx', 'xlsx', 'md', 'markdown', 'txt'])

const props = defineProps<{ courseId: number }>()

const loading = ref(true)
const loadError = ref('')
const knowledgeBases = ref<KnowledgeBaseView[]>([])
const selectedKbId = ref<number>()
const selectedKb = computed(() => knowledgeBases.value.find((kb) => kb.id === selectedKbId.value))

const indexStatusLabels: Record<string, string> = {
  empty: '未入库',
  ready: '可问答',
  needs_rebuild: '需重建索引',
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const response = await api.knowledgeBaseAdminList(props.courseId)
    knowledgeBases.value = response.data
    if (!knowledgeBases.value.some((kb) => kb.id === selectedKbId.value)) {
      selectedKbId.value = knowledgeBases.value[0]?.id
    }
  } catch (error: unknown) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

const dialogVisible = ref(false)
const saving = ref(false)
const editingKb = ref<KnowledgeBaseView>()
const formError = ref('')
const form = reactive({ name: '' })

function openKb(kb?: KnowledgeBaseView): void {
  editingKb.value = kb
  formError.value = ''
  Object.assign(form, {
    name: kb?.name ?? '',
  })
  dialogVisible.value = true
}

async function saveKb(): Promise<void> {
  if (!form.name.trim()) {
    formError.value = '请输入知识库名称'
    return
  }
  saving.value = true
  formError.value = ''
  const body = {
    name: form.name.trim(),
  }
  try {
    if (editingKb.value) {
      await api.knowledgeBaseAdminUpdate(props.courseId, editingKb.value.id, body)
    } else {
      const created = await api.knowledgeBaseAdminCreate(props.courseId, body)
      selectedKbId.value = created.data.id
    }
    dialogVisible.value = false
    await load()
    ElMessage.success('知识库已保存')
  } catch (error: unknown) {
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function removeKb(kb: KnowledgeBaseView): Promise<void> {
  if (!(await confirm(`确定删除知识库“${kb.name}”吗?其中的文档与分块将一并删除。`, '删除知识库'))) return
  try {
    await api.knowledgeBaseAdminDelete(props.courseId, kb.id)
    await load()
    ElMessage.success('知识库已删除')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

const documents = ref<KbDocumentView[]>([])
const documentsLoading = ref(false)

const documentStateLabels: Record<string, string> = {
  pending: '等待入库',
  parsing: '解析中',
  indexing: '向量化中',
  ready: '已就绪',
  error: '入库失败',
}

async function loadDocuments(): Promise<void> {
  const kb = selectedKb.value
  if (!kb) {
    documents.value = []
    return
  }
  documentsLoading.value = true
  try {
    const response = await api.knowledgeBaseAdminListDocuments(props.courseId, kb.id)
    documents.value = response.data
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    documentsLoading.value = false
  }
}

watch(selectedKbId, () => {
  documents.value = []
  void loadDocuments()
})

async function removeDocument(document: KbDocumentView): Promise<void> {
  const kb = selectedKb.value
  if (!kb) return
  if (!(await confirm(`确定从知识库移除文档“${document.name}”吗?`, '移除文档'))) return
  try {
    await api.knowledgeBaseAdminDeleteDocument(props.courseId, kb.id, document.id)
    await Promise.all([loadDocuments(), load()])
    ElMessage.success('文档已移除')
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

const uploading = ref(false)
const uploadTopRef = ref<UploadInstance>()

function indexable(name: string): boolean {
  return INDEXABLE_SUFFIXES.has(name.split('.').pop()?.toLowerCase() ?? '')
}

async function uploadAndIngest(uploadFile: UploadFile): Promise<void> {
  const kb = selectedKb.value
  const file = uploadFile.raw
  if (!kb || !file) return
  const invalid = indexable(file.name)
    ? validateUploadFile(file)
    : '只支持 pdf、docx、pptx、xlsx、md、txt'
  if (invalid) {
    clearUploads()
    ElMessage.error(invalid)
    return
  }
  uploading.value = true
  try {
    const { materialId, name } = await uploadCourseMaterial(props.courseId, file, null)
    ElMessage.success(`“${name}”已存入课程资料库`)
    const started = await api.knowledgeBaseAdminIngestDocument(props.courseId, kb.id, { materialId })
    await Promise.all([loadDocuments(), load()])
    openProgress('document', '文档入库', started.data.id)
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  } finally {
    uploading.value = false
    clearUploads()
  }
}

function clearUploads(): void {
  uploadTopRef.value?.clearFiles()
}

const pickerVisible = ref(false)
const pickerLoading = ref(false)
const pickerFiles = ref<MaterialView[]>([])
const selectedMaterialId = ref<number>()

async function openPicker(): Promise<void> {
  pickerVisible.value = true
  pickerLoading.value = true
  selectedMaterialId.value = undefined
  try {
    pickerFiles.value = await collectIndexableFiles(null)
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    pickerLoading.value = false
  }
}

async function collectIndexableFiles(parentId: number | null): Promise<MaterialView[]> {
  const response = await api.courseMaterialList(props.courseId, parentId === null ? {} : { parentId })
  const folders = response.data.filter((item) => item.kind === 'folder')
  const files = response.data.filter(
    (item) =>
      item.kind === 'file' &&
      item.state === 'active' &&
      INDEXABLE_SUFFIXES.has(item.name.split('.').pop()?.toLowerCase() ?? ''),
  )
  const nested = await Promise.all(folders.map((folder) => collectIndexableFiles(folder.id)))
  return [...files, ...nested.flat()]
}

const progressVisible = ref(false)
const progressTitle = ref('')
const progressLines = ref<string[]>([])
const progressPercent = ref<number>()
const progressActive = ref(false)
const progressKind = ref<'document' | 'rebuild'>('document')
const progressDocumentId = ref<number>()
let attachAbort: AbortController | null = null

function handleIngestEvent(event: KbIngestEvent): 'running' | 'done' | 'failed' {
  if (event.type === 'stage') {
    progressLines.value.push(event.message)
    progressPercent.value = undefined
  } else if (event.type === 'parse_progress') {
    progressLines.value.splice(-1, 1, event.message)
  } else if (event.type === 'embedding_progress') {
    progressPercent.value = Math.round((event.current / event.total) * 100)
  } else if (event.type === 'document_start') {
    progressLines.value.push(`(${event.index}/${event.total})${event.name}`)
    progressPercent.value = undefined
  } else if (event.type === 'done') {
    return 'done'
  } else if (event.type === 'error') {
    progressLines.value.push(`失败:${event.message}`)
    return 'failed'
  } else if (event.type === 'status') {
    // 任务已不在跑:按持久化状态收尾
    if (event.state === 'error') {
      progressLines.value.push(`失败:${event.errorMessage || '入库失败'}`)
      return 'failed'
    }
    if (event.state === 'ready' || event.active === false) return 'done'
  }
  return 'running'
}

function openProgress(kind: 'document' | 'rebuild', title: string, documentId?: number): void {
  progressKind.value = kind
  progressDocumentId.value = documentId
  progressTitle.value = title
  progressLines.value = []
  progressPercent.value = undefined
  progressVisible.value = true
  progressActive.value = true
  void attachProgress()
}

/** attach 循环:断线只重连,不影响后台任务;done/error/status 收尾 */
async function attachProgress(): Promise<void> {
  attachAbort?.abort()
  const abort = new AbortController()
  attachAbort = abort
  const connect = async (): Promise<void> => {
    const result = { outcome: 'running' as 'running' | 'done' | 'failed' }
    const onEvent = (event: KbIngestEvent): void => {
      const applied = handleIngestEvent(event)
      if (applied !== 'running') result.outcome = applied
    }
    const stream =
      progressKind.value === 'document'
        ? streamDocumentEvents(props.courseId, selectedKbId.value!, progressDocumentId.value!, onEvent, abort.signal)
        : streamRebuildEvents(props.courseId, selectedKbId.value!, onEvent, abort.signal)
    await stream.catch(() => undefined)
    if (abort.signal.aborted) return
    await Promise.all([loadDocuments(), load()])
    if (result.outcome === 'done') {
      progressActive.value = false
      progressVisible.value = false
      ElMessage.success(progressKind.value === 'document' ? '文档已入库' : '索引已重建')
      return
    }
    if (result.outcome === 'failed') {
      progressActive.value = false
      return
    }
    // 连接断了但任务还在后台:静默重连
    window.setTimeout(() => {
      if (!abort.signal.aborted && progressVisible.value) void connect()
    }, 2000)
  }
  await connect()
}

/** 关闭进度窗:任务继续在后台跑,列表按状态轮询刷新 */
function closeProgress(): void {
  attachAbort?.abort()
  attachAbort = null
  progressVisible.value = false
  void Promise.all([loadDocuments(), load()])
}

async function startIngest(): Promise<void> {
  const kb = selectedKb.value
  const materialId = selectedMaterialId.value
  if (!kb || !materialId) {
    ElMessage.warning('请先选择要入库的资料')
    return
  }
  pickerVisible.value = false
  try {
    const started = await api.knowledgeBaseAdminIngestDocument(props.courseId, kb.id, { materialId })
    await Promise.all([loadDocuments(), load()])
    openProgress('document', '文档入库', started.data.id)
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  }
}

async function retryDocument(document: KbDocumentView): Promise<void> {
  const kb = selectedKb.value
  if (!kb) return
  try {
    await api.knowledgeBaseAdminRetryDocument(props.courseId, kb.id, document.id)
    await loadDocuments()
    openProgress('document', '重新入库', document.id)
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  }
}

async function rebuildIndex(): Promise<void> {
  const kb = selectedKb.value
  if (!kb) return
  if (
    !(await confirm('将按当前向量模型配置重新生成整个知识库的索引;重建写入新索引,完成前问答继续用旧索引。确定重建吗?', '重建索引'))
  )
    return
  try {
    await api.knowledgeBaseAdminRebuild(props.courseId, kb.id)
    openProgress('rebuild', '重建索引')
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  }
}

/** 显式中止:后台任务在安全边界停下并以失败收尾(可重试) */
async function cancelProgress(): Promise<void> {
  const kb = selectedKb.value
  if (!kb) return
  try {
    if (progressKind.value === 'document' && progressDocumentId.value !== undefined) {
      await api.knowledgeBaseAdminCancelDocument(props.courseId, kb.id, progressDocumentId.value)
    } else if (progressKind.value === 'rebuild') {
      await api.knowledgeBaseAdminCancelRebuild(props.courseId, kb.id)
    }
  } catch (cause: unknown) {
    ElMessage.error(errorMessage(cause))
  }
}

const hasRunningDocuments = computed(() =>
  documents.value.some((doc) => doc.state === 'pending' || doc.state === 'parsing' || doc.state === 'indexing'),
)
let refreshTimer: number | undefined
watch(hasRunningDocuments, (runningNow) => {
  if (runningNow && refreshTimer === undefined) {
    refreshTimer = window.setInterval(() => {
      void Promise.all([loadDocuments(), load()])
    }, 3000)
  } else if (!runningNow && refreshTimer !== undefined) {
    window.clearInterval(refreshTimer)
    refreshTimer = undefined
  }
})

/** needs_rebuild 时先重建再入库(后端同样 409 拦截,这里提前禁用) */
const ingestBlocked = computed(() => selectedKb.value?.indexStatus === 'needs_rebuild')

watch(
  () => props.courseId,
  () => {
    pickerVisible.value = false
    attachAbort?.abort()
    progressVisible.value = false
    selectedKbId.value = undefined
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <section>
    <AsyncState :loading="loading" :error="loadError" :empty="false" @retry="load">
      <div v-if="knowledgeBases.length === 0" class="kb-onboarding">
        <h3>还没有知识库</h3>
        <el-button type="primary" :icon="Plus" @click="openKb()">创建知识库</el-button>
      </div>

      <div v-else class="kb-layout">
        <aside class="kb-list">
          <div class="kb-list-head">
            <span class="kb-count">{{ knowledgeBases.length }} 个知识库</span>
            <el-button size="small" :icon="Plus" @click="openKb()">新建</el-button>
          </div>
          <button
            v-for="kb in knowledgeBases"
            :key="kb.id"
            type="button"
            class="kb-item"
            :class="{ selected: kb.id === selectedKbId }"
            @click="selectedKbId = kb.id"
          >
            <span class="kb-item-name">{{ kb.name }}</span>
            <span class="kb-item-meta">
              <span class="status-dot" :class="kb.indexStatus" />
              {{ indexStatusLabels[kb.indexStatus] }} · {{ kb.documentCount }} 篇文档
            </span>
          </button>
        </aside>

        <div v-if="selectedKb" class="kb-detail">
          <header class="kb-detail-head">
            <div class="kb-detail-title">
              <h3>{{ selectedKb.name }}</h3>
              <el-tag
                :type="
                  selectedKb.indexStatus === 'ready'
                    ? 'success'
                    : selectedKb.indexStatus === 'needs_rebuild'
                      ? 'warning'
                      : 'info'
                "
                effect="light"
                round
              >
                {{ indexStatusLabels[selectedKb.indexStatus] }}
              </el-tag>
            </div>
            <div class="kb-detail-actions">
              <el-upload
                ref="uploadTopRef"
                class="upload-inline"
                :auto-upload="false"
                :show-file-list="false"
                :disabled="uploading || ingestBlocked"
                accept=".pdf,.docx,.pptx,.xlsx,.md,.markdown,.txt"
                :on-change="uploadAndIngest"
              >
                <el-button type="primary" :icon="UploadFilled" :loading="uploading" :disabled="ingestBlocked">
                  {{ uploading ? '正在上传…' : '上传文档' }}
                </el-button>
              </el-upload>
              <el-button :icon="FolderOpened" :disabled="ingestBlocked" @click="openPicker">从课程资料选择</el-button>
              <el-button :icon="Refresh" :disabled="selectedKb.indexStatus === 'empty'" @click="rebuildIndex">
                重建索引
              </el-button>
              <el-button link :icon="Edit" @click="openKb(selectedKb)">重命名</el-button>
              <el-button link type="danger" :icon="Delete" @click="removeKb(selectedKb)">删除</el-button>
            </div>
          </header>
          <p class="kb-detail-meta">更新于 {{ formatDateTime(selectedKb.updatedAt) }}</p>

          <el-alert
            v-if="selectedKb.indexStatus === 'needs_rebuild'"
            type="warning"
            title="需要重建索引后才能继续使用"
            :closable="false"
            class="kb-alert"
          />

          <div v-if="!documentsLoading && documents.length === 0" class="kb-docs-empty">
            <p>这个知识库还没有文档。</p>
            <p class="hint">支持 pdf、docx、pptx、xlsx、md、txt</p>
          </div>
          <template v-else>
            <el-table v-loading="documentsLoading" :data="documents" row-key="id">
              <el-table-column prop="name" label="文档" min-width="260" show-overflow-tooltip />
              <el-table-column label="状态" width="110">
                <template #default="{ row }: { row: KbDocumentView }">
                  <el-tag
                    :type="row.state === 'ready' ? 'success' : row.state === 'error' ? 'danger' : 'info'"
                    effect="plain"
                  >
                    {{ documentStateLabels[row.state] }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="分块数" width="90" prop="chunkCount" />
              <el-table-column label="操作" width="150" align="right">
                <template #default="{ row }: { row: KbDocumentView }">
                  <el-tooltip
                    v-if="row.state === 'error' && row.materialId === null"
                    content="源文件已删除,无法重新解析"
                  >
                    <el-button link disabled>重试</el-button>
                  </el-tooltip>
                  <el-button
                    v-else-if="row.state === 'error'"
                    link
                    type="primary"
                    :disabled="ingestBlocked"
                    @click="retryDocument(row)"
                  >
                    重试
                  </el-button>
                  <el-button link type="danger" @click="removeDocument(row)">移除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div
              v-for="document in documents.filter((item) => item.state === 'error' && item.errorMessage)"
              :key="document.id"
              class="doc-error"
            >
              「{{ document.name }}」{{ document.errorMessage }}
            </div>
          </template>
        </div>
      </div>
    </AsyncState>

    <el-dialog
      v-model="dialogVisible"
      :title="editingKb ? '重命名知识库' : '创建知识库'"
      width="480px"
      destroy-on-close
    >
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="dialog-alert" />
      <el-form :model="form" label-position="top" @submit.prevent="saveKb">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="100" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveKb">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pickerVisible" title="从课程资料选择" width="560px" destroy-on-close>
      <p class="hint">支持 pdf、docx、pptx、xlsx、md、txt</p>
      <el-select
        v-model="selectedMaterialId"
        v-loading="pickerLoading"
        placeholder="选择课程资料"
        class="picker-select"
        filterable
      >
        <el-option v-for="file in pickerFiles" :key="file.id" :label="file.name" :value="file.id" />
      </el-select>
      <p v-if="!pickerLoading && pickerFiles.length === 0" class="hint">
        本课程还没有可入库的资料文件,请先在「资料库 → 资料」上传。
      </p>
      <template #footer>
        <el-button @click="pickerVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedMaterialId" @click="startIngest">开始入库</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="progressVisible"
      :title="progressTitle"
      width="520px"
      :close-on-click-modal="false"
      :before-close="closeProgress"
    >
      <ul class="progress-lines">
        <li v-for="(line, index) in progressLines" :key="index">{{ line }}</li>
      </ul>
      <el-progress v-if="progressPercent !== undefined" :percentage="progressPercent" />
      <template #footer>
        <el-button v-if="progressActive" type="danger" @click="cancelProgress">中止</el-button>
        <el-button @click="closeProgress">关闭</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.kb-onboarding {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 0;
}

.kb-onboarding h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
}

.kb-layout {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.kb-list {
  width: 264px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.kb-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 2px 4px;
}

.kb-count {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.kb-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background-color 0.15s;
}

.kb-item:hover {
  border-color: var(--el-color-primary-light-5);
}

.kb-item.selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.kb-item-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-item-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--el-color-info);
}

.status-dot.ready {
  background: var(--el-color-success);
}

.status-dot.needs_rebuild {
  background: var(--el-color-warning);
}

.kb-detail {
  flex: 1;
  min-width: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  padding: 18px 20px;
  background: var(--el-bg-color);
}

.kb-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.kb-detail-title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.kb-detail-title h3 {
  margin: 0;
  font-size: 17px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-detail-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.kb-detail-meta {
  margin: 6px 0 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.kb-alert {
  margin-bottom: 14px;
}

.kb-docs-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 48px 16px;
  border: 1px dashed var(--el-border-color);
  border-radius: 10px;
  color: var(--el-text-color-secondary);
}

.kb-docs-empty p {
  margin: 0;
}

.doc-error {
  margin-top: 8px;
  color: var(--el-color-danger);
  font-size: 13px;
}

.dialog-alert {
  margin-bottom: 14px;
}

.hint {
  margin: 0 0 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.picker-select {
  width: 100%;
}

.upload-inline {
  display: inline-flex;
}

.progress-lines {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
  display: grid;
  gap: 6px;
  font-size: 13px;
}
</style>
