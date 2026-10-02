import type { GenerateQuestionsRequest } from '@/api/generated'
import { streamPost } from '@/api/sse'
import type { CourseQuestionTypeValue } from '@/features/courses/question'

/**
 * 草稿题:与后端 DraftQuestion / 保存接口的请求体字段一致。
 * issues 非空表示模型修复后仍有问题,需教师手工修正后才能通过保存校验。
 */
export interface DraftQuestion {
  title: string
  type: CourseQuestionTypeValue
  stemMarkdown: string
  options: string[] | null
  answer: string | string[] | boolean | null
  analysisMarkdown: string
  issues: string[]
}

export interface QuizTemplate {
  questionId: string
  topic: string
  type: CourseQuestionTypeValue
  difficulty: string
}

type GenerateQuestionsEvent =
  | { type: 'stage'; phase: 'parsing' | 'exploring' | 'planning' | 'quizzing' }
  | { type: 'progress'; message: string }
  | { type: 'explore_text'; text: string }
  | { type: 'tool'; name: string; phase: 'start' | 'end'; summary: string }
  | { type: 'notice'; message: string }
  | { type: 'plan'; analysis: string; templates: QuizTemplate[] }
  | { type: 'question'; index: number; total: number; draft: DraftQuestion }
  | { type: 'heartbeat' }
  | { type: 'done'; total: number }
  | { type: 'error'; message: string }

/** 出题(不落库,教师审阅勾选后走既有创建接口保存) */
export function streamGenerateQuestions(
  courseId: number,
  request: GenerateQuestionsRequest,
  onEvent: (event: GenerateQuestionsEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  return streamPost(`/api/v1/courses/${courseId}/questions/generate`, request, onEvent, signal)
}
