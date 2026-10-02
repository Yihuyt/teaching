<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Edit, Plus } from '@element-plus/icons-vue'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type {
  KnowledgeBaseView,
  TutorAdminSessionView,
  TutorAssistantDefaults,
  TutorAssistantView,
  TutorMountView,
  TutorTranscriptView,
} from '@/api/generated'
import type { TutorSource } from '@/features/tutor/tutorStream'
import AsyncState from '@/shared/components/AsyncState.vue'
import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'
import TutorChatPanel from '@/features/tutor/components/TutorChatPanel.vue'
import TutorSources from '@/features/tutor/components/TutorSources.vue'
import { formatDateTime } from '@/shared/format'

const props = defineProps<{ courseId: number }>()

const activeTab = ref('assistants')

const assistantsLoading = ref(true)
const assistantsError = ref('')
const assistants = ref<TutorAssistantView[]>([])
const knowledgeBases = ref<KnowledgeBaseView[]>([])

async function loadAssistants(): Promise<void> {
  assistantsLoading.value = true
  assistantsError.value = ''
  try {
    const [assistantResponse, kbResponse] = await Promise.all([
      api.tutorListAssistants(props.courseId),
      api.knowledgeBaseAdminList(props.courseId),
    ])
    assistants.value = assistantResponse.data
    knowledgeBases.value = kbResponse.data
  } catch (error: unknown) {
    assistantsError.value = errorMessage(error)
  } finally {
    assistantsLoading.value = false
  }
}

const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref<TutorAssistantView>()
const formError = ref('')
/** 常用模型(百炼 DashScope);也可直接输入其它模型名 */
const MODEL_PRESETS = ['qwen-plus', 'qwen-max', 'qwen-turbo', 'qwen-long', 'qwen3-max', 'deepseek-v3']
const defaults = ref<TutorAssistantDefaults | null>(null)
const form = reactive({
  name: '',
  description: '',
  instructions: '',
  model: '',
  temperature: 0.2,
  reasoning: false,
  maxRounds: 8,
  visibleToStudents: true,
  knowledgeBaseIds: [] as number[],
})

async function openDialog(assistant?: TutorAssistantView): Promise<void> {
  editing.value = assistant
  formError.value = ''
  if (!defaults.value) {
    try {
      defaults.value = (await api.tutorAssistantDefaults(props.courseId)).data
    } catch (error: unknown) {
      ElMessage.error(errorMessage(error))
      return
    }
  }
  const base = assistant ?? defaults.value
  Object.assign(form, {
    name: assistant?.name ?? '',
    description: assistant?.description ?? '',
    instructions: assistant?.instructions ?? '',
    model: base.model,
    temperature: base.temperature,
    reasoning: base.reasoning,
    maxRounds: base.maxRounds,
    visibleToStudents: assistant?.visibleToStudents ?? true,
    knowledgeBaseIds: assistant?.knowledgeBases.map((m) => m.id) ?? [],
  })
  dialogVisible.value = true
}

async function saveAssistant(): Promise<void> {
  if (!form.name.trim()) {
    formError.value = '请输入助手名称'
    return
  }
  if (!form.model.trim()) {
    formError.value = '请选择或输入模型'
    return
  }
  saving.value = true
  formError.value = ''
  const body = {
    name: form.name.trim(),
    description: form.description,
    instructions: form.instructions,
    model: form.model.trim(),
    temperature: form.temperature,
    reasoning: form.reasoning,
    maxRounds: form.maxRounds,
    visibleToStudents: form.visibleToStudents,
    knowledgeBaseIds: form.knowledgeBaseIds,
  }
  try {
    if (editing.value) await api.tutorUpdateAssistant(props.courseId, editing.value.id, body)
    else await api.tutorCreateAssistant(props.courseId, body)
    dialogVisible.value = false
    ElMessage.success('已保存')
    await loadAssistants()
  } catch (error: unknown) {
    formError.value = errorMessage(error)
  } finally {
    saving.value = false
  }
}

async function removeAssistant(assistant: TutorAssistantView): Promise<void> {
  if (!(await confirm(`删除助手「${assistant.name}」?其下所有学生会话将一并删除。`, '删除助手'))) return
  try {
    await api.tutorDeleteAssistant(props.courseId, assistant.id)
    ElMessage.success('已删除')
    await loadAssistants()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

function mountNames(mounts: TutorMountView[]): string {
  return mounts.length ? mounts.map((m) => (m.ready ? m.name : `${m.name}(未就绪)`)).join('、') : '—'
}

const testAssistantId = ref<number | null>(null)
const testAssistant = computed(() => assistants.value.find((a) => a.id === testAssistantId.value) ?? null)

const sessions = ref<TutorAdminSessionView[]>([])
const total = ref(0)
const page = ref(1)
const filterAssistantId = ref<number | null>(null)
const loading = ref(false)
const drawerVisible = ref(false)
const transcript = ref<TutorTranscriptView>()
const transcriptLoading = ref(false)

async function loadSessions(): Promise<void> {
  loading.value = true
  try {
    const response = await api.tutorAdminSessions(props.courseId, {
      ...(filterAssistantId.value ? { assistantId: filterAssistantId.value } : {}),
      page: page.value,
      size: 20,
    })
    sessions.value = response.data.items
    total.value = response.data.total
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function openTranscript(session: TutorAdminSessionView): Promise<void> {
  drawerVisible.value = true
  transcriptLoading.value = true
  transcript.value = undefined
  try {
    transcript.value = (await api.tutorAdminTranscript(props.courseId, session.id)).data
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  } finally {
    transcriptLoading.value = false
  }
}

function sourcesOf(sources: unknown): TutorSource[] {
  return Array.isArray(sources) ? (sources as TutorSource[]) : []
}

function withCitations(content: string): string {
  return content.replace(/\[source-(\d+)\]/g, (_, n: string) => `<sup class="cite">[${n}]</sup>`)
}

watch(activeTab, (tab) => {
  if (tab === 'records') {
    page.value = 1
    void loadSessions()
  }
})

watch(filterAssistantId, () => {
  page.value = 1
  void loadSessions()
})

watch(
  () => props.courseId,
  () => {
    activeTab.value = 'assistants'
    drawerVisible.value = false
    sessions.value = []
    testAssistantId.value = null
    filterAssistantId.value = null
    void loadAssistants()
  },
  { immediate: true },
)
</script>

<template>
  <section class="panel">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="助手配置" name="assistants">
        <div class="toolbar">
          <el-button type="primary" :icon="Plus" @click="openDialog()">新建助手</el-button>
        </div>
        <AsyncState
          :loading="assistantsLoading"
          :error="assistantsError"
          :empty="assistants.length === 0"
          empty-text="还没有助手,点「新建助手」创建第一个"
          @retry="loadAssistants"
        >
          <el-table :data="assistants" row-key="id">
            <el-table-column prop="name" label="助手" min-width="160">
              <template #default="{ row }: { row: TutorAssistantView }">
                <strong>{{ row.name }}</strong>
                <p v-if="row.description" class="cell-desc">{{ row.description }}</p>
              </template>
            </el-table-column>
            <el-table-column label="知识库" min-width="180">
              <template #default="{ row }: { row: TutorAssistantView }">{{
                mountNames(row.knowledgeBases)
              }}</template>
            </el-table-column>
            <el-table-column label="模型" width="170">
              <template #default="{ row }: { row: TutorAssistantView }">
                {{ row.model }}
                <span class="cell-desc"
                  >温度 {{ row.temperature }}{{ row.reasoning ? ' · 深度思考' : '' }}</span
                >
              </template>
            </el-table-column>
            <el-table-column label="对学生开放" width="110">
              <template #default="{ row }: { row: TutorAssistantView }">
                <el-tag :type="row.visibleToStudents ? 'success' : 'info'" effect="plain" size="small">
                  {{ row.visibleToStudents ? '开放' : '关闭' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" align="right">
              <template #default="{ row }: { row: TutorAssistantView }">
                <el-button link :icon="Edit" @click="openDialog(row)">编辑</el-button>
                <el-button link type="danger" :icon="Delete" @click="removeAssistant(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </AsyncState>
      </el-tab-pane>

      <el-tab-pane label="问答测试" name="test">
        <div class="toolbar">
          <el-select v-model="testAssistantId" placeholder="选择要测试的助手" style="width: 280px" clearable>
            <el-option v-for="a in assistants" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
          <span class="hint">学生端只看到开放的助手。</span>
        </div>
        <TutorChatPanel
          v-if="activeTab === 'test' && testAssistant"
          :course-id="courseId"
          :assistant="testAssistant"
        />
        <p v-else class="hint">先在上方选择一个助手。</p>
      </el-tab-pane>

      <el-tab-pane label="问答记录" name="records">
        <div class="toolbar">
          <el-select v-model="filterAssistantId" placeholder="全部助手" style="width: 240px" clearable>
            <el-option v-for="a in assistants" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </div>
        <el-table v-loading="loading" :data="sessions" row-key="id">
          <el-table-column prop="accountName" label="学生" width="140" />
          <el-table-column prop="assistantName" label="助手" width="160" show-overflow-tooltip />
          <el-table-column prop="title" label="对话" min-width="220" show-overflow-tooltip />
          <el-table-column prop="messageCount" label="消息数" width="90" />
          <el-table-column label="最近活动" width="180">
            <template #default="{ row }: { row: TutorAdminSessionView }">
              {{ formatDateTime(row.updatedAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }: { row: TutorAdminSessionView }">
              <el-button link type="primary" @click="openTranscript(row)">查看对话</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="page"
          :page-size="20"
          :total="total"
          layout="total, prev, pager, next"
          class="pagination"
          @current-change="loadSessions"
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="dialogVisible"
      :title="editing ? '编辑助手' : '新建助手'"
      width="680px"
      destroy-on-close
    >
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" class="dialog-alert" />
      <el-form :model="form" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="60" show-word-limit placeholder="请输入助手名称" />
        </el-form-item>
        <el-form-item label="简介(学生可见)">
          <el-input
            v-model="form.description"
            maxlength="300"
            show-word-limit
            placeholder="一句话说明它能帮学生做什么"
          />
        </el-form-item>
        <el-form-item label="补充要求(教师提示词,学生不可见)">
          <el-input
            v-model="form.instructions"
            type="textarea"
            :rows="5"
            maxlength="4000"
            show-word-limit
            placeholder="请输入补充要求"
          />
        </el-form-item>
        <el-divider content-position="left">模型</el-divider>
        <el-form-item label="模型">
          <el-select v-model="form.model" filterable allow-create default-first-option style="width: 100%">
            <el-option v-for="m in MODEL_PRESETS" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <div class="model-row">
          <el-form-item label="温度" class="model-row__temperature">
            <el-slider
              v-model="form.temperature"
              :min="0"
              :max="2"
              :step="0.1"
              show-input
              :show-input-controls="false"
            />
          </el-form-item>
          <el-form-item label="工具轮次上限">
            <el-input-number v-model="form.maxRounds" :min="1" :max="16" />
          </el-form-item>
          <el-form-item label="深度思考">
            <el-switch v-model="form.reasoning" />
          </el-form-item>
        </div>
        <el-divider content-position="left">挂载</el-divider>
        <el-form-item label="挂载知识库">
          <el-select
            v-model="form.knowledgeBaseIds"
            multiple
            style="width: 100%"
            placeholder="不挂载则仅凭通用知识作答"
          >
            <el-option
              v-for="kb in knowledgeBases"
              :key="kb.id"
              :label="kb.indexStatus === 'ready' ? kb.name : `${kb.name}(索引未就绪)`"
              :value="kb.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="对学生开放">
          <el-switch v-model="form.visibleToStudents" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAssistant">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="drawerVisible"
      size="60%"
      :title="
        transcript
          ? `${transcript.session.accountName} · ${transcript.session.assistantName} · ${transcript.session.title}`
          : '对话'
      "
    >
      <div v-loading="transcriptLoading" class="transcript">
        <div v-for="message in transcript?.messages ?? []" :key="message.id" :class="['msg', message.role]">
          <div v-if="message.role === 'user'" class="bubble bubble--user">{{ message.content }}</div>
          <div v-else class="bubble bubble--assistant">
            <MarkdownRenderer :source="withCitations(message.content)" />
            <TutorSources :course-id="courseId" :sources="sourcesOf(message.sources)" />
          </div>
        </div>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-bottom: 12px;
}

.hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.cell-desc {
  margin: 2px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.dialog-alert {
  margin-bottom: 12px;
}

.model-row {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.model-row__temperature {
  flex: 1;
  min-width: 0;
}

.pagination {
  margin-top: 12px;
  justify-content: flex-end;
}

.transcript {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.msg {
  display: flex;
}

.msg.user {
  justify-content: flex-end;
}

.bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.6;
}

.bubble--user {
  background: var(--el-color-primary-light-9);
  white-space: pre-wrap;
}

.bubble--assistant {
  background: var(--el-fill-color-light);
  width: 100%;
  max-width: 100%;
}

.bubble--assistant :deep(.cite) {
  color: var(--el-color-primary);
  font-size: 11px;
}
</style>
