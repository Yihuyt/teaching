import { streamPost, streamPostMultipart } from '@/api/sse'
import type { GenerateStageRequest, OutlineRequest, RegenerateSceneRequest } from '@/api/generated'
import type { OutlineImage, OutlineScene } from '@/features/courseware/dsl'

export type GenerationEvent =
  /** 某一步的过程说明(重试原因等);order 为 0 表示大纲阶段 */
  | { type: 'trace'; order: number; message: string }
  | { type: 'outline'; title: string; scenes: OutlineScene[]; images: OutlineImage[] }
  | { type: 'scene_start'; order: number; total: number; title: string }
  | { type: 'scene_done'; order: number; sceneId: string; version: number; warnings: string[] }
  /** 该页生成失败,已以占位页落库(标题 / 类型 / 概要保留),可单页重生成 */
  | { type: 'scene_failed'; order: number; title: string; sceneId: string; message: string }
  | { type: 'done'; version: number; total?: number; failed?: number }
  | { type: 'error'; message: string }

/** 出大纲:到 outline 事件为止;大纲不落库,由教师确认后再生成 */
export function streamGenerateOutline(
  courseId: number,
  coursewareId: number,
  request: OutlineRequest,
  onEvent: (event: GenerationEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamPost(`/api/v1/courses/${courseId}/coursewares/${coursewareId}/outline`, request, onEvent, signal)
}

/** 按确认后的大纲生成整份课件:清空课件后逐页落库,每页完成即持久;signal 中止保住已落库的页 */
export function streamGenerateStage(
  courseId: number,
  coursewareId: number,
  request: GenerateStageRequest,
  onEvent: (event: GenerationEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamPost(`/api/v1/courses/${courseId}/coursewares/${coursewareId}/generate`, request, onEvent, signal)
}

/** 生成讲稿与动作:按教师改定的内容给各页写讲稿,逐页落库;scope=missing 只补没有讲稿的页,all 全部重写 */
export function streamGenerateSpeech(
  courseId: number,
  coursewareId: number,
  scope: 'missing' | 'all',
  onEvent: (event: GenerationEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamPost(`/api/v1/courses/${courseId}/coursewares/${coursewareId}/speech`, { scope }, onEvent, signal)
}

export function streamRegenerateScene(
  courseId: number,
  coursewareId: number,
  sceneId: string,
  request: RegenerateSceneRequest,
  onEvent: (event: GenerationEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamPost(
    `/api/v1/courses/${courseId}/coursewares/${coursewareId}/scenes/${encodeURIComponent(sceneId)}/regenerate`,
    request,
    onEvent,
    signal,
  )
}

/** 课堂问答回答动作(服务端已过滤,type 只会是 highlight) */
export interface QaAnswerAction {
  type: string
  target: string
}

/** 课堂问答回答:audio 缺省/null 表示语音合成失败(纯文本呈现) */
export interface QaAnswer {
  text: string
  actions: QaAnswerAction[]
  audio?: { base64: string; format: string } | null
}

type QaAskEvent =
  | { type: 'answer_delta'; text: string }
  | { type: 'retry'; round: number; reason: string }
  | { type: 'done'; answer: QaAnswer }
  | { type: 'error'; message: string }

export function streamAskQuestion(
  courseId: number,
  coursewareId: number,
  request: { sceneId: string; question: string },
  onEvent: (event: QaAskEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamPost(
    `/api/v1/courses/${courseId}/coursewares/${coursewareId}/questions`,
    request,
    onEvent,
    signal,
  )
}

type TtsEvent =
  | { type: 'progress'; done: number; total: number }
  | { type: 'done'; coursewareId: number; version: number; generated: number; skipped: number }
  | { type: 'error'; message: string }

interface TtsSummary {
  version: number
  generated: number
  skipped: number
}

/** 合成整课缺音频的讲稿段并落库;完成后调用方重取课件拿新音频地址 */
export async function streamSynthesizeTts(
  courseId: number,
  coursewareId: number,
  onProgress: (done: number, total: number) => void,
  signal?: AbortSignal,
): Promise<TtsSummary> {
  let result: TtsSummary | null = null
  await streamPost<TtsEvent>(
    `/api/v1/courses/${courseId}/coursewares/${coursewareId}/tts`,
    undefined,
    (event) => {
      if (event.type === 'progress') onProgress(event.done, event.total)
      else if (event.type === 'done') {
        result = { version: event.version, generated: event.generated, skipped: event.skipped }
      } else throw new Error(event.message)
    },
    signal,
  )
  if (!result) throw new Error('语音合成未返回结果')
  return result
}

interface MaterialImage {
  id: string
  sourceDocumentName: string
  /** 来源文档里的页码(0 表示无页码) */
  pageNumber: number
  width: number
  height: number
  description: string
}

type ParseMaterialsEvent =
  | { type: 'progress'; message: string }
  | {
      type: 'done'
      bundleId: number
      chars: number
      truncated: boolean
      totalRawChars: number
      imageCount: number
      visionImageCount: number
      images: (MaterialImage & { visionPriority: number; url: string })[]
    }
  | { type: 'error'; message: string }

/**
 * 上传 ≤5 份文件解析合并为一个素材包挂在课件名下(文件顺序即合并顺序;原件进课件自有存储区,不进资料库;
 * 密钥在服务端按课程取用,浏览器不接触)
 */
export function streamParseMaterials(
  courseId: number,
  coursewareId: number,
  files: File[],
  onEvent: (event: ParseMaterialsEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  const form = new FormData()
  for (const file of files) form.append('files', file, file.name)
  return streamPostMultipart(
    `/api/v1/courses/${courseId}/coursewares/${coursewareId}/materials`,
    form,
    onEvent,
    signal,
  )
}
