/**
 * 积木编程助手一轮对话的 SSE 消费端。
 *
 * 这是「一律走 orval 生成客户端」规则的记录在案偏离：该端点是 text/event-stream
 * 增量流，读流复用 api/sse;这里只负责请求体、HTTP 错误文案与事件分发。
 * 事件为 `data: {json}`，以 type 区分(与 SseSupport 一致)：
 * content{text}(给学生的回复增量,来自 final_answer 工具)/ reply_reset(上一次回复被打回,正文从头再来)/ narration{text}(模型在工具之间说的话)/ round{role}(一轮结束)/ tool{name,phase,error} /
 * notice{message} / script{script} / question{text,options} / browser_tool{callId,name,args}(要在编辑器里执行并回传)/
 * done{messageId,seq} / error{message}。
 */
import { csrfHeaderEntry } from '@/api/client'
import { consumeStream } from '@/api/sse'
import type { ScriptView } from '@/api/generated'
import type { HarvestResult } from '@/features/blockcoding/scratchBridge'

interface ChatStreamRequest {
  sessionId: number
  content: string
  /** 作品此刻的快照:修改模式必带;讲解模式不读作品,不带 */
  harvest?: HarvestResult | undefined
  quotes?: { label: string; sprite: string; blockId: string; xml: string }[]
  /** 管理后台创作台选的模式(chat / agent);普通界面不传,一律讲解 */
  mode?: string
}

export interface InsertScript {
  sprite: string
  xml: string
  variables: string[]
  lists: string[]
  localVariables: string[]
  localLists: string[]
  broadcasts: string[]
  definedProcedures: string[]
}

interface ChatStreamHandlers {
  onContent: (text: string) => void
  onReplyReset: () => void
  onNarration: (text: string) => void
  onRound: (role: 'narration' | 'finish') => void
  onTool: (name: string, phase: 'start' | 'end', error: boolean) => void
  onNotice: (message: string) => void
  onScript: (script: ScriptView) => void
  onQuestion: (text: string, options: string[]) => void
  onBrowserTool: (callId: string, name: string, args: Record<string, unknown>) => void
  onDone: (result: { messageId: number; seq: number }) => void
  onError: (message: string) => void
}

type StreamEvent =
  | { type: 'content'; text: string }
  | { type: 'reply_reset' }
  | { type: 'narration'; text: string }
  | { type: 'round'; role: 'narration' | 'finish' }
  | { type: 'tool'; name: string; phase: 'start' | 'end'; error?: boolean }
  | { type: 'notice'; message: string }
  | { type: 'script'; script: ScriptView }
  | { type: 'question'; text: string; options: string[] }
  | { type: 'browser_tool'; callId: string; name: string; args: Record<string, unknown> }
  | { type: 'done'; messageId: number; seq: number }
  | { type: 'error'; message: string }

/** 发送一条消息并消费 SSE 流。signal 由调用方持有:请求发出到响应头到达之间也能停 */
export async function streamChatMessage(
  request: ChatStreamRequest,
  handlers: ChatStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const [csrfName, csrfValue] = await csrfHeaderEntry()

  const response = await fetch(`/api/v1/blockcoding/chat-sessions/${request.sessionId}/messages`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      [csrfName]: csrfValue,
    },
    body: JSON.stringify({ content: request.content, harvest: request.harvest ?? null, quotes: request.quotes ?? [], mode: request.mode ?? null }),
    signal,
  })

  if (!response.ok || !response.body) {
    let detail = '编程助手暂时不可用，请稍后再试'
    try {
      const problem = (await response.json()) as { detail?: string }
      if (problem.detail) {
        detail = problem.detail
      }
    } catch {
      // 非 JSON 错误体，用默认文案
    }
    handlers.onError(detail)
    return
  }

  let finished = false
  const dispatch = (event: StreamEvent): void => {
    switch (event.type) {
      case 'content':
        handlers.onContent(event.text)
        break
      case 'reply_reset':
        handlers.onReplyReset()
        break
      case 'narration':
        handlers.onNarration(event.text)
        break
      case 'round':
        handlers.onRound(event.role)
        break
      case 'tool':
        handlers.onTool(event.name, event.phase, event.error === true)
        break
      case 'notice':
        handlers.onNotice(event.message)
        break
      case 'script':
        handlers.onScript(event.script)
        break
      case 'question':
        handlers.onQuestion(event.text, event.options ?? [])
        break
      case 'browser_tool':
        handlers.onBrowserTool(event.callId, event.name, event.args ?? {})
        break
      case 'done':
        finished = true
        handlers.onDone({ messageId: event.messageId, seq: event.seq })
        break
      case 'error':
        finished = true
        handlers.onError(event.message)
        break
    }
  }
  // 响应头到达即返回,正文在后台继续读;调用方靠 handlers 得知结束
  void consumeStream<StreamEvent>(response, dispatch)
    .then(() => {
      if (!finished) handlers.onError('回答中断了，请重新发送这条消息')
    })
    .catch(() => {
      if (!signal.aborted) handlers.onError('连接中断，请检查网络后重试')
    })
}
