/**
 * SSE 消费的通用泵。
 *
 * 属「一律走 orval 生成客户端」规则的记录在案偏离:流式端点是
 * text/event-stream 增量流,必须用 fetch + ReadableStream。
 * CSRF 令牌复用 client.ts 的同一份缓存。事件格式为 `data: {json}`,
 * 以 JSON 里的 type 字段区分(与后端 ai.SseSupport 的事件协议一一对应)。
 * 各功能目录下的 *Stream.ts 都经这里读流。
 */
import { csrfHeaderEntry } from '@/api/client'

/** 流式端点在建立阶段就被拒(非 2xx):带状态码,便于调用方区分"该重连"与"别再试" */
export class SseHttpError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'SseHttpError'
    this.status = status
  }
}

export function drainSseBuffer(buffer: string): { payloads: string[]; rest: string } {
  const payloads: string[] = []
  const parts = buffer.split('\n\n')
  const rest = parts.pop() ?? ''
  for (const part of parts) {
    const dataLines = part
      .split('\n')
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice('data:'.length).trim())
    if (dataLines.length > 0) {
      payloads.push(dataLines.join('\n'))
    }
  }
  return { payloads, rest }
}

function extractProblemDetail(body: string): string {
  try {
    const problem = JSON.parse(body) as { title?: string; detail?: string }
    if (problem.detail) return problem.detail
  } catch {
    // 非 JSON 错误体,用截断原文
  }
  return body.slice(0, 300) || '请求失败'
}

export async function streamPost<E>(
  url: string,
  body: unknown,
  onEvent: (event: E) => void,
  signal?: AbortSignal,
): Promise<void> {
  const [csrfName, csrfValue] = await csrfHeaderEntry()
  const response = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      [csrfName]: csrfValue,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    ...(signal ? { signal } : {}),
  })
  await consumeStream<E>(response, onEvent)
}

/** multipart 型 SSE(文件上传后流式返回处理进度):Content-Type 由浏览器按 FormData 自动带 boundary */
export async function streamPostMultipart<E>(
  url: string,
  form: FormData,
  onEvent: (event: E) => void,
  signal?: AbortSignal,
): Promise<void> {
  const [csrfName, csrfValue] = await csrfHeaderEntry()
  const response = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'text/event-stream', [csrfName]: csrfValue },
    body: form,
    ...(signal ? { signal } : {}),
  })
  await consumeStream<E>(response, onEvent)
}

/** GET 型 SSE(如构建进度 attach 端点):无请求体,无需 CSRF */
export async function streamGet<E>(
  url: string,
  onEvent: (event: E) => void,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(url, {
    method: 'GET',
    credentials: 'same-origin',
    headers: { Accept: 'text/event-stream' },
    ...(signal ? { signal } : {}),
  })
  await consumeStream<E>(response, onEvent)
}

export async function consumeStream<E>(response: Response, onEvent: (event: E) => void): Promise<void> {
  if (!response.ok || !response.body) {
    const text = await response.text().catch(() => '')
    throw new SseHttpError(response.status, extractProblemDetail(text))
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  // 递归泵而不是 while+await:流式读取本质是顺序的
  const pump = async (): Promise<void> => {
    const { done, value } = await reader.read()
    if (done) return
    buffer += decoder.decode(value, { stream: true })
    const { payloads, rest } = drainSseBuffer(buffer)
    buffer = rest
    for (const payload of payloads) {
      onEvent(JSON.parse(payload) as E)
    }
    return pump()
  }
  await pump()
}
