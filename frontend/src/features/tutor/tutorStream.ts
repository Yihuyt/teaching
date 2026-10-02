/**
 * 智能问答的 SSE 消费端(通用泵在 api/sse.ts,记录在案的 orval 偏离)。
 * 事件协议与后端 TutorChatService 一一对应:每一轮模型输出都实时流出,
 * 轮结束时回标 narration(过程叙述,只进轨迹)或 finish(正式回答)。
 */
import { streamPost } from '@/api/sse'

/** 来源条目:知识库段落;ref 即正文里的 [source-N] */
export interface TutorSource {
  ref: string
  kind: 'kb'
  kbId: number
  kbName: string
  documentName: string
  section: string
  snippet: string
}

export type TutorEvent =
  | { type: 'user_message'; id: number }
  | { type: 'seed_sources'; sources: TutorSource[] }
  | { type: 'round'; callId: string; phase: 'start'; label: string }
  | { type: 'round'; callId: string; phase: 'end'; role: 'narration' | 'finish' }
  | { type: 'thinking'; callId: string; text: string }
  | { type: 'content'; callId: string; text: string }
  | { type: 'tool'; callId: string; toolCallId: string; name: string; phase: 'start'; args: string }
  | {
      type: 'tool'
      callId: string
      toolCallId: string
      name: string
      phase: 'end'
      error: boolean
      summary: string
      sources: TutorSource[]
    }
  | { type: 'notice'; message: string }
  | { type: 'heartbeat' }
  | { type: 'done'; assistantMessageId: number; sources: TutorSource[] }
  | { type: 'title'; title: string }
  | { type: 'error'; message: string }

export interface TutorSendRequest {
  question: string
}

export function streamTutorMessage(
  courseId: number,
  sessionId: number,
  request: TutorSendRequest,
  onEvent: (event: TutorEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamPost(
    `/api/v1/courses/${courseId}/tutor/sessions/${sessionId}/messages`,
    { question: request.question },
    onEvent,
    signal,
  )
}

export interface TutorTraceRound {
  callId: string
  label: string
  thinking: string
  /** narration 轮的正文;finish 轮为空(正文进答案) */
  narration: string
  role: 'narration' | 'finish' | null
  tools: TutorTraceTool[]
}

interface TutorTraceTool {
  toolCallId: string
  name: string
  args: string
  running: boolean
  error: boolean
  summary: string
}

/**
 * 流式回答的累积状态(narration 过滤):
 * content 增量先进当前轮缓冲;轮结束 role=narration → 缓冲移入轨迹;role=finish → 缓冲即答案。
 */
export interface TutorStreamState {
  answer: string
  rounds: TutorTraceRound[]
  sources: TutorSource[]
  notices: string[]
  done: boolean
}

export function emptyStreamState(): TutorStreamState {
  return { answer: '', rounds: [], sources: [], notices: [], done: false }
}

export function applyTutorEvent(state: TutorStreamState, event: TutorEvent): void {
  switch (event.type) {
    case 'round': {
      if (event.phase === 'start') {
        state.rounds.push({
          callId: event.callId,
          label: event.label,
          thinking: '',
          narration: '',
          role: null,
          tools: [],
        })
      } else {
        const round = state.rounds.find((r) => r.callId === event.callId)
        if (round) {
          round.role = event.role
          if (event.role === 'finish') {
            // 正文已实时写进 answer;finish 轮不保留叙述
            round.narration = ''
          } else {
            // 叙述轮:把已流出的正文从答案移到轨迹
            round.narration = state.answer
            state.answer = ''
          }
        }
      }
      break
    }
    case 'thinking': {
      const round = state.rounds.find((r) => r.callId === event.callId)
      if (round) round.thinking += event.text
      break
    }
    case 'content':
      state.answer += event.text
      break
    case 'tool': {
      const round = state.rounds.find((r) => r.callId === event.callId)
      if (!round) break
      if (event.phase === 'start') {
        round.tools.push({
          toolCallId: event.toolCallId,
          name: event.name,
          args: event.args,
          running: true,
          error: false,
          summary: '',
        })
      } else {
        const tool = round.tools.find((t) => t.toolCallId === event.toolCallId)
        if (tool) {
          tool.running = false
          tool.error = event.error
          tool.summary = event.summary
        }
        state.sources.push(...event.sources)
      }
      break
    }
    case 'seed_sources':
      state.sources.push(...event.sources)
      break
    case 'notice':
      state.notices.push(event.message)
      break
    case 'done':
      state.sources = event.sources
      state.done = true
      break
    default:
      break
  }
}
