/**
 * 知识库入库/重建进度的 SSE 消费端(通用泵在 api/sse.ts,记录在案的 orval 偏离)。
 * 进度端点是 attach 型 GET:任务在后台跑,关页面/断线不影响任务,
 * attach 时先收到已发生事件的重放,再续接实时流;任务不在跑时收到一条 status 后流即结束。
 */
import { streamGet } from '@/api/sse'

export type KbIngestEvent =
  | { type: 'stage'; stage: 'parsing' | 'chunking' | 'indexing'; message: string }
  | { type: 'parse_progress'; message: string }
  | { type: 'embedding_progress'; current: number; total: number }
  | { type: 'document_start'; documentId: number; name: string; index: number; total: number }
  | { type: 'done'; documentId?: number; chunkCount?: number; total?: number }
  | { type: 'status'; state?: string; errorMessage?: string; active?: boolean }
  | { type: 'error'; message: string }

export function streamDocumentEvents(
  courseId: number,
  kbId: number,
  documentId: number,
  onEvent: (event: KbIngestEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamGet(
    `/api/v1/courses/${courseId}/knowledge-bases/${kbId}/documents/${documentId}/events`,
    onEvent,
    signal,
  )
}

export function streamRebuildEvents(
  courseId: number,
  kbId: number,
  onEvent: (event: KbIngestEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamGet(`/api/v1/courses/${courseId}/knowledge-bases/${kbId}/rebuild/events`, onEvent, signal)
}
