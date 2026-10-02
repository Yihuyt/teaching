/**
 * 教材构建知识图谱的 SSE 消费端(通用泵在 api/sse.ts,记录在案的 orval 偏离)。
 * 进度端点是 attach 型 GET:任务在后台跑,断线重连不影响任务,
 * attach 时先收到已发生事件的重放,再续接实时流。
 */
import { streamGet } from '@/api/sse'

type KgBuildEvent =
  | { type: 'stage'; stage: 'queued' | 'parsing' | 'toc' | 'extracting' | 'merging'; label: string }
  | { type: 'parse_progress'; message: string }
  | { type: 'toc_ready'; degraded: boolean }
  | { type: 'progress'; current: number; total: number; sectionTitle: string }
  | { type: 'extracted'; nodeCount: number; edgeCount: number; warnings: string[] }
  | { type: 'status'; status: string }
  | { type: 'error'; message: string }

export function streamBuildEvents(
  courseId: number,
  buildId: number,
  onEvent: (event: KgBuildEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamGet(`/api/v1/courses/${courseId}/knowledge-graph-builds/${buildId}/events`, onEvent, signal)
}
