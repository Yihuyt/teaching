<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'

import { confirm } from '@/shared/dialogs'
import { api, errorMessage } from '@/api/client'
import type { TutorMessageView, TutorMountView, TutorSessionView } from '@/api/generated'
import {
  applyTutorEvent,
  emptyStreamState,
  streamTutorMessage,
  type TutorSource,
  type TutorStreamState,
  type TutorTraceRound,
} from '@/features/tutor/tutorStream'
import MarkdownRenderer from '@/shared/components/MarkdownRenderer.vue'
import TutorSources from '@/features/tutor/components/TutorSources.vue'
import TutorTrace from '@/features/tutor/components/TutorTrace.vue'

const props = defineProps<{
  courseId: number
  assistant: { id: number; name: string; knowledgeBases: TutorMountView[] }
}>()

interface ChatMessage {
  id: number | null
  role: 'user' | 'assistant'
  content: string
  sources: TutorSource[]
  rounds: TutorTraceRound[]
  notices: string[]
  streaming: boolean
}

const sessions = ref<TutorSessionView[]>([])
const activeSessionId = ref<number | null>(null)
const messages = ref<ChatMessage[]>([])
const input = ref('')
const running = ref(false)
const loading = ref(false)
const errorText = ref('')
const listEl = ref<HTMLElement>()
const highlightRef = ref<string | null>(null)
let abort: AbortController | null = null

async function loadAll(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const sessionsResponse = await api.tutorListSessions(props.courseId, props.assistant.id)
    sessions.value = sessionsResponse.data
    if (sessions.value.length) {
      await selectSession(sessions.value[0]!.id)
    } else {
      activeSessionId.value = null
      messages.value = []
    }
  } catch (error: unknown) {
    errorText.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

function fromHistory(message: TutorMessageView): ChatMessage {
  const trace = (message.trace ?? null) as {
    narrations?: string[]
    tools?: { round: number; tool: string; arguments: string; result: string; error: boolean }[]
  } | null
  const rounds: TutorTraceRound[] = []
  if (trace) {
    ;(trace.narrations ?? []).forEach((narration, index) => {
      rounds.push({
        callId: `h-n-${index}`,
        label: '探索',
        thinking: '',
        narration,
        role: 'narration',
        tools: [],
      })
    })
    ;(trace.tools ?? []).forEach((tool, index) => {
      const round = rounds[Math.min(tool.round - 1, rounds.length - 1)]
      const entry = {
        toolCallId: `h-t-${index}`,
        name: tool.tool,
        args: tool.arguments,
        running: false,
        error: tool.error,
        summary: tool.result.slice(0, 120),
      }
      if (round) round.tools.push(entry)
      else
        rounds.push({
          callId: `h-r-${index}`,
          label: '探索',
          thinking: '',
          narration: '',
          role: 'narration',
          tools: [entry],
        })
    })
  }
  return {
    id: message.id,
    role: message.role === 'assistant' ? 'assistant' : 'user',
    content: message.content,
    sources: (message.sources as TutorSource[] | null) ?? [],
    rounds,
    notices: [],
    streaming: false,
  }
}

async function selectSession(sessionId: number): Promise<void> {
  if (running.value) return
  activeSessionId.value = sessionId
  try {
    const response = await api.tutorMessages(props.courseId, sessionId)
    messages.value = response.data.map(fromHistory)
    await scrollToBottom()
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

async function createSession(): Promise<void> {
  if (running.value) return
  try {
    const response = await api.tutorCreateSession(props.courseId, props.assistant.id)
    sessions.value.unshift(response.data)
    activeSessionId.value = response.data.id
    messages.value = []
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

async function removeSession(session: TutorSessionView): Promise<void> {
  if (running.value) return
  if (!(await confirm(`删除对话「${session.title}」?`, '删除对话'))) return
  try {
    await api.tutorDeleteSession(props.courseId, session.id)
    sessions.value = sessions.value.filter((s) => s.id !== session.id)
    if (activeSessionId.value === session.id) {
      if (sessions.value.length) await selectSession(sessions.value[0]!.id)
      else {
        activeSessionId.value = null
        messages.value = []
      }
    }
  } catch (error: unknown) {
    ElMessage.error(errorMessage(error))
  }
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
}

async function send(): Promise<void> {
  const question = input.value.trim()
  if (!question || running.value) return
  if (activeSessionId.value === null) {
    await createSession()
    if (activeSessionId.value === null) return
  }
  const sessionId = activeSessionId.value
  input.value = ''
  running.value = true
  messages.value.push({
    id: null,
    role: 'user',
    content: question,
    sources: [],
    rounds: [],
    notices: [],
    streaming: false,
  })
  messages.value.push({
    id: null,
    role: 'assistant',
    content: '',
    sources: [],
    rounds: [],
    notices: [],
    streaming: true,
  })
  // 取数组里的响应式代理再改字段,直接改原始对象不会触发渲染
  const reply = messages.value[messages.value.length - 1]!
  const state: TutorStreamState = emptyStreamState()
  abort = new AbortController()
  await scrollToBottom()
  try {
    await streamTutorMessage(
      props.courseId,
      sessionId,
      { question },
      (event) => {
        if (event.type === 'error') throw new Error(event.message)
        if (event.type === 'title') {
          const session = sessions.value.find((s) => s.id === sessionId)
          if (session) session.title = event.title
          return
        }
        if (event.type === 'done') reply.id = event.assistantMessageId
        applyTutorEvent(state, event)
        reply.content = state.answer
        reply.rounds = state.rounds
        reply.sources = state.sources
        reply.notices = state.notices
        void scrollToBottom()
      },
      abort.signal,
    )
    if (!state.done) {
      reply.notices = [...reply.notices, '回答未完成(连接中断)']
    }
  } catch (error: unknown) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      reply.notices = [...reply.notices, '已停止生成']
    } else {
      reply.notices = [...reply.notices, errorMessage(error)]
    }
  } finally {
    reply.streaming = false
    running.value = false
    abort = null
    const session = sessions.value.find((s) => s.id === sessionId)
    if (session) {
      sessions.value = [session, ...sessions.value.filter((s) => s.id !== sessionId)]
    }
  }
}

function stop(): void {
  abort?.abort()
}

/**
 * [source-N] → 可点角标(MarkdownRenderer 经 DOMPurify 放行 sup/data-ref)。
 * 模型偶尔把标注写成行内代码 `[source-N]`,反引号里的内容会被当代码原样转义,所以先把包着标注的反引号去掉。
 */
function withCitations(content: string): string {
  return content
    .replace(/`\[source-(\d+)\]`/g, '[source-$1]')
    .replace(/\[source-(\d+)\]/g, (_, n: string) => `<sup class="tutor-cite" data-ref="source-${n}">[${n}]</sup>`)
}

function onAnswerClick(event: MouseEvent): void {
  const target = event.target as HTMLElement | null
  const sourceRef = target?.closest('.tutor-cite')?.getAttribute('data-ref')
  if (!sourceRef) return
  highlightRef.value = sourceRef
  document.getElementById(`tutor-src-${sourceRef}`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void send()
  }
}

watch(
  () => [props.courseId, props.assistant.id],
  () => {
    abort?.abort()
    void loadAll()
  },
  { immediate: true },
)

onBeforeUnmount(() => abort?.abort())
</script>

<template>
  <div class="tutor">
    <aside class="tutor-sessions">
      <el-button :icon="Plus" size="small" class="tutor-new" :disabled="running" @click="createSession"
        >新对话</el-button
      >
      <ul class="tutor-session-list">
        <li
          v-for="session in sessions"
          :key="session.id"
          :class="{ active: session.id === activeSessionId }"
          @click="selectSession(session.id)"
        >
          <span class="tutor-session-title">{{ session.title }}</span>
          <el-button
            link
            size="small"
            :icon="Delete"
            :disabled="running"
            @click.stop="removeSession(session)"
          />
        </li>
      </ul>
    </aside>

    <section class="tutor-main">
      <el-alert v-if="errorText" type="error" :title="errorText" :closable="false" />
      <div ref="listEl" class="tutor-list" @click="onAnswerClick">
        <p v-if="!loading && messages.length === 0" class="tutor-empty">
          向「{{ assistant.name }}」提问
        </p>
        <div
          v-for="(message, index) in messages"
          :key="message.id ?? `m-${index}`"
          :class="['tutor-msg', message.role]"
        >
          <div v-if="message.role === 'user'" class="tutor-bubble tutor-bubble--user">
            {{ message.content }}
          </div>
          <div v-else class="tutor-bubble tutor-bubble--assistant">
            <TutorTrace :rounds="message.rounds" :running="message.streaming" />
            <el-alert
              v-for="(notice, i) in message.notices"
              :key="i"
              type="warning"
              :title="notice"
              :closable="false"
              class="tutor-notice"
            />
            <MarkdownRenderer v-if="message.content" :source="withCitations(message.content)" />
            <p v-else-if="message.streaming" class="tutor-pending">…</p>
            <TutorSources :course-id="courseId" :sources="message.sources" :highlight="highlightRef" />
          </div>
        </div>
      </div>
      <div class="tutor-input">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          maxlength="2000"
          placeholder="输入问题,Enter 发送,Shift+Enter 换行"
          :disabled="running"
          @keydown="onKeydown"
        />
        <el-button v-if="running" type="danger" plain @click="stop">停止</el-button>
        <el-button v-else type="primary" :disabled="!input.trim()" @click="send">发送</el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.tutor {
  display: flex;
  gap: 12px;
  min-height: 560px;
  height: calc(100vh - 260px);
}

.tutor-sessions {
  width: 220px;
  flex: none;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: 8px;
}

.tutor-new {
  margin-bottom: 8px;
}

.tutor-session-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
}

.tutor-session-list li {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}

.tutor-session-list li:hover {
  background: var(--el-fill-color-light);
}

.tutor-session-list li.active {
  background: var(--el-color-primary-light-9);
}

.tutor-session-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tutor-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}


.tutor-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tutor-empty {
  color: var(--el-text-color-secondary);
  text-align: center;
  margin-top: 40px;
}

.tutor-msg {
  display: flex;
}

.tutor-msg.user {
  justify-content: flex-end;
}

.tutor-bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.6;
}

.tutor-bubble--user {
  background: var(--el-color-primary-light-9);
  white-space: pre-wrap;
}

.tutor-bubble--assistant {
  background: var(--el-fill-color-light);
  width: 100%;
  max-width: 100%;
}

.tutor-notice {
  margin-bottom: 6px;
}

.tutor-pending {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.tutor-bubble--assistant :deep(.tutor-cite) {
  color: var(--el-color-primary);
  cursor: pointer;
  font-size: 11px;
  margin-left: 2px;
}

.tutor-input {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

.tutor-input :deep(.el-textarea) {
  flex: 1;
}
</style>
