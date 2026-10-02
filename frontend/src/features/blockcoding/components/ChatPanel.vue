<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { confirm } from '@/shared/dialogs'
import { errorMessage } from '@/api/client'
import { streamChatMessage } from '@/features/blockcoding/chatStream'
import type { MessageView } from '@/api/generated'
import { useEditorChanges } from '@/features/blockcoding/useEditorChanges'
import { useQuotes } from '@/features/blockcoding/useQuotes'
import ChatComposer from '@/features/blockcoding/components/ChatComposer.vue'
import ChatMessageItem from '@/features/blockcoding/components/ChatMessageItem.vue'
import type { ScratchBridge } from '@/features/blockcoding/scratchBridge'
import { useBlockCodingStore } from '@/features/blockcoding/store'

const props = defineProps<{
  projectId: number
  bridge: ScratchBridge
}>()

const emit = defineEmits<{
  /** 标题栏空白处按下:外层拿它当拖动把手(按钮、开关上按下不算) */
  grab: [event: PointerEvent]
}>()

function handleHeaderPointerDown(event: PointerEvent): void {
  const target = event.target as HTMLElement | null
  if (target?.closest('button, input, .el-radio-group, [role="button"]')) return
  emit('grab', event)
}

const store = useBlockCodingStore()

const TOOL_LABELS: Record<string, string> = {
  read_skill: '读技能',
  write_script: '写脚本',
  list_project: '看作品',
  ask_user: '提问',
  create_sprite: '新建角色',
  delete_script: '删脚本',
  delete_sprite: '删角色',
  delete_variable: '删变量',
  delete_list: '删列表',
}

const sending = ref(false)
const streamError = ref('')
const messageListEl = ref<HTMLDivElement | null>(null)
const panelEl = ref<HTMLElement | null>(null)
const composer = ref<InstanceType<typeof ChatComposer> | null>(null)
/** 这一轮的取消开关:发送一开始就建好,停止按钮在响应头到达前按下也有效 */
let abortStream: AbortController | null = null

const { reverting, runBrowserTool, revertChanges } = useEditorChanges(props.bridge, store)
const { chipLabels, dragOver, takeDraft, restoreDraft } = useQuotes({
  bridge: props.bridge,
  composer,
  panel: panelEl,
  accepting: () => !sending.value,
})

const modes = computed(() => (store.management ? (store.session?.modes ?? ['chat']) : ['chat']))
const selectedMode = ref<'chat' | 'agent'>('chat')
watch(modes, (list) => {
  selectedMode.value = (list[0] as 'chat' | 'agent') ?? 'chat'
}, { immediate: true })
const agentMode = computed(() => selectedMode.value === 'agent')

const answerableMessageId = computed(() => {
  const last = store.messages[store.messages.length - 1]
  return last && last.role === 'assistant' && last.question ? last.id : null
})

const processLine = computed(() => {
  const steps = store.streaming?.steps ?? []
  const counts = new Map<string, number>()
  for (const step of steps) {
    if (step.phase !== 'end' || step.name === 'final_answer') continue
    const key = step.name === 'write_script' ? (step.error ? '编译有错' : '写好') : (TOOL_LABELS[step.name] ?? step.name)
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  const parts: string[] = []
  for (const [key, count] of counts) {
    if (key === '写好') parts.push(`写好 ${count} 段脚本`)
    else if (key === '编译有错') parts.push(`改了 ${count} 处编译错误`)
    else parts.push(count > 1 ? `${key} ×${count}` : key)
  }
  const running = steps[steps.length - 1]
  if (running && running.phase === 'start') parts.push(`${TOOL_LABELS[running.name] ?? running.name}…`)
  return parts.join(' · ')
})

const narrationLine = computed(() => {
  const text = (store.streaming?.roundText || store.streaming?.narration || '').replace(/\s+/g, ' ').trim()
  return text.length > 80 ? `…${text.slice(-80)}` : text
})

async function scrollToBottom(): Promise<void> {
  await nextTick()
  messageListEl.value?.scrollTo({ top: messageListEl.value.scrollHeight })
}

watch(() => store.messages, scrollToBottom, { deep: false })
watch(() => store.streaming?.roundText, scrollToBottom)

async function send(text?: string): Promise<void> {
  const content = (text ?? composer.value?.read() ?? '').trim()
  if (!content || sending.value || !store.session) {
    return
  }
  sending.value = true
  streamError.value = ''
  // 手打的消息带编辑区里还在的积木,并清空输入框;点选项作答不碰输入框和它的草稿
  const fromComposer = text === undefined
  const { attached: quotes, draft } = fromComposer ? takeDraft() : { attached: [], draft: [] }
  try {
    if (fromComposer) composer.value?.clear()
    store.messages = [
      ...store.messages,
      {
        id: -Date.now(),
        seq: store.messages.length + 1,
        role: 'user',
        content,
        scripts: quotes.map((quote, index) => ({
          id: index + 1,
          sprite: quote.sprite,
          code: quote.code,
          blockCount: quote.blockCount,
          kind: 'quoted',
          label: quote.label,
          blockId: null,
          previous: null,
        })),
        sprites: [],
        variables: [],
        question: null,
        reverted: false,
        createdAt: new Date().toISOString(),
      },
    ]
    store.streaming = { text: '', roundText: '', narration: '', steps: [], scripts: [] }
    await scrollToBottom()

    const harvest = agentMode.value ? await props.bridge.harvest() : undefined
    const sessionId = store.session.id
    const controller = new AbortController()
    abortStream = controller
    await streamChatMessage(
      { sessionId, content, harvest, quotes: quotes.map(({ label, sprite, blockId, xml }) => ({ label, sprite, blockId, xml })), mode: selectedMode.value },
      {
        onContent(delta) {
          if (store.streaming) store.streaming.text += delta
        },
        onReplyReset() {
          if (store.streaming) store.streaming.text = ''
        },
        onNarration(delta) {
          if (store.streaming) store.streaming.roundText += delta
        },
        onRound() {
          if (!store.streaming) return
          if (store.streaming.roundText) store.streaming.narration = store.streaming.roundText
          store.streaming.roundText = ''
        },
        onTool(name, phase, error) {
          store.streaming?.steps.push({ name, phase, error })
        },
        onNotice(message) {
          ElMessage.info(message)
        },
        onScript(script) {
          store.streaming?.scripts.push(script)
        },
        onQuestion() {
          // 提问随 done 落库后由消息列表渲染选项
        },
        onBrowserTool(callId, name, args) {
          void runBrowserTool(sessionId, callId, name, args).catch((error) => ElMessage.error(errorMessage(error)))
        },
        async onDone() {
          store.streaming = null
          sending.value = false
          await store.refreshMessages()
        },
        onError(message) {
          store.streaming = null
          sending.value = false
          streamError.value = message
          void store.refreshMessages()
        },
      },
      controller.signal,
    )
  } catch (error) {
    store.streaming = null
    sending.value = false
    streamError.value = errorMessage(error)
    if (fromComposer) {
      restoreDraft(draft)
      composer.value?.restore(content, draft)
    }
  }
}

async function revert(message: MessageView): Promise<void> {
  const outcome = await revertChanges(message)
  if (outcome.status === 'failed') ElMessage.error(`回退时出错：${outcome.reason}`)
  else if (outcome.status === 'done' && outcome.unrestorable.length > 0) ElMessage.warning(`已回退，但角色 ${outcome.unrestorable.join('、')} 是作品里原有的，造型和声音恢复不了`)
  else if (outcome.status === 'done' && outcome.skipped > 0) ElMessage.warning(`已回退，但有 ${outcome.skipped} 段原积木所在的角色已不在，没有放回`)
  else if (outcome.status === 'done') ElMessage.success('已回退这轮改动')
}

/** 用户按了停止:断开这次连接,面板立刻回到可输入;服务端跑完当前这轮就停,不写进作品、不留助手消息 */
async function stopGeneration(): Promise<void> {
  abortStream?.abort()
  abortStream = null
  store.streaming = null
  sending.value = false
  await store.refreshMessages()
}

async function clearConversation(): Promise<void> {
  if (!(await confirm('清空这个作品的全部对话记录？作品里的积木不受影响。', '清空对话'))) return
  // 正在生成的那轮先停掉,不然服务端会继续往编辑器里写
  if (sending.value) await stopGeneration()
  try {
    await store.clearSession(props.projectId)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  }
}

onBeforeUnmount(() => {
  abortStream?.abort()
})
</script>

<template>
  <aside ref="panelEl" class="chat-panel" :class="{ 'chat-panel--drop': dragOver }">
    <div v-if="dragOver" class="chat-panel__drop-hint">松开，把这段积木交给助手</div>
    <header class="chat-panel__header" @pointerdown="handleHeaderPointerDown">
      <span class="chat-panel__title">积木助手</span>
      <el-radio-group v-if="modes.length > 1" v-model="selectedMode" size="small" :disabled="sending" class="chat-panel__mode">
        <el-radio-button value="agent">修改</el-radio-button>
        <el-radio-button value="chat">讲解</el-radio-button>
      </el-radio-group>
      <span class="chat-panel__spacer" />
      <el-button v-if="store.messages.length > 0" size="small" text @click="clearConversation">清空对话</el-button>
      <slot name="header-actions" />
    </header>

    <div ref="messageListEl" class="chat-panel__messages">
      <p v-if="store.messages.length === 0 && !store.streaming" class="chat-panel__empty">
        <template v-if="agentMode">比如“让小猫碰到边缘就反弹”</template>
        <template v-else>比如“要让小猫跳起来该用哪些积木”</template>
      </p>
      <ChatMessageItem
        v-for="message in store.messages"
        :key="message.id"
        :message="message"
        :answerable="message.id === answerableMessageId"
        :reverting="reverting"
        @answer="send"
        @revert="revert(message)"
      />
      <div v-if="store.streaming" class="chat-panel__streaming">
        <div class="chat-panel__process">
          <el-icon class="is-loading"><i /></el-icon>
          <span class="chat-panel__process-text">{{ processLine || (agentMode ? '正在看你的作品…' : '正在想…') }}</span>
        </div>
        <div v-if="narrationLine" class="chat-panel__narration">{{ narrationLine }}</div>
        <div v-if="store.streaming.text" class="chat-panel__draft">{{ store.streaming.text }}</div>
      </div>
      <el-alert
        v-if="streamError"
        :title="streamError"
        type="error"
        :closable="true"
        @close="streamError = ''"
      />
    </div>

    <footer class="chat-panel__composer">
      <div class="chat-panel__input-row">
        <ChatComposer
          ref="composer"
          :placeholder="agentMode ? '要做什么？Enter 发送，Shift+Enter 换行' : '想问什么？Enter 发送，Shift+Enter 换行'"
          :disabled="sending"
          @submit="send()"
          @change="(labels) => (chipLabels = labels)"
        />
        <el-button v-if="sending" type="danger" class="chat-panel__stop" @click="stopGeneration">停止</el-button>
        <el-button v-else type="primary" @click="send()">发送</el-button>
      </div>
    </footer>
  </aside>
</template>

<style scoped>
.chat-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-width: 0;
  background: #fff;
}

.chat-panel--drop {
  outline: 2px dashed var(--el-color-primary);
  outline-offset: -4px;
  background: var(--el-color-primary-light-9);
}

.chat-panel__drop-hint {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-color-primary);
  font-size: 15px;
  pointer-events: none;
}


.chat-panel__header {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: move;
  user-select: none;
}

.chat-panel__title {
  font-weight: 600;
}

.chat-panel__spacer {
  flex: 1;
}

.chat-panel__mode :deep(.el-radio-button__inner) {
  padding: 4px 10px;
}

.chat-panel__messages {
  flex: 1;
  padding: 8px 12px;
  overflow-y: auto;
}

.chat-panel__empty {
  margin: 24px 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.chat-panel__streaming {
  margin: 6px 0;
}

.chat-panel__process {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.chat-panel__process-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-panel__narration {
  padding: 2px 10px 0;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat-panel__draft {
  padding: 8px 12px;
  white-space: pre-wrap;
  font-size: 14px;
  line-height: 1.6;
}

.chat-panel__composer {
  padding: 8px 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.chat-panel__input-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}

</style>
