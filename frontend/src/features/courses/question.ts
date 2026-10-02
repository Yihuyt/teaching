export type CourseQuestionTypeValue = 'single_choice' | 'fill_in_blank' | 'true_false'

export const BLANK_MARK = '____'

export function insertBlankMark(text: string, start: number, end: number): { text: string; cursor: number } {
  const before = text.slice(0, start)
  const after = text.slice(end)
  const lead = before === '' || /\s$/.test(before) ? '' : ' '
  const tail = after === '' || /^\s/.test(after) ? '' : ' '
  const inserted = lead + BLANK_MARK + tail
  return { text: before + inserted + after, cursor: start + inserted.length }
}

export const DEFAULT_ITEM_SCORE = 10

function isChoice(type: CourseQuestionTypeValue): boolean {
  return type === 'single_choice'
}

export function readQuestionOptions(
  type: CourseQuestionTypeValue,
  options: unknown,
  subject = '题目',
): string[] {
  if (!isChoice(type)) {
    if (options !== null) {
      throw new Error(`${subject}携带了当前题型不允许的选项`)
    }
    return []
  }
  if (!Array.isArray(options) || options.length < 2) {
    throw new Error('至少需要两个选项')
  }
  const blank = options.findIndex((option) => typeof option !== 'string' || option.trim().length === 0)
  if (blank >= 0) {
    throw new Error(`选项 ${optionLetter(blank)} 不能为空`)
  }
  const seen = new Map<string, number>()
  for (const [index, option] of (options as string[]).entries()) {
    const first = seen.get(option)
    if (first !== undefined) {
      throw new Error(`选项 ${optionLetter(first)} 与 ${optionLetter(index)} 内容重复`)
    }
    seen.set(option, index)
  }
  return options as string[]
}

export function optionLetter(index: number): string {
  return String.fromCharCode(65 + index)
}

export function assertQuestionAnswer(
  type: CourseQuestionTypeValue,
  options: unknown,
  answer: unknown,
  subject = '标准答案',
): void {
  const optionValues = readQuestionOptions(type, options, subject)
  if (type === 'single_choice' && (typeof answer !== 'string' || !optionValues.includes(answer))) {
    throw new Error(`${subject}必须是一个已有选项`)
  }
  if (
    type === 'fill_in_blank' &&
    (typeof answer !== 'string' || answer.trim().length === 0 || answer.length > 200)
  ) {
    throw new Error(`${subject}必须是非空且不超过 200 字符的文本`)
  }
  if (type === 'true_false' && typeof answer !== 'boolean') {
    throw new Error(`${subject}必须是布尔值`)
  }
}

/** 题干与题型一致性:填空题恰好一处 ____,其它题型不得含该记号(与服务端同规则) */
export function assertQuestionStem(type: CourseQuestionTypeValue, stem: string): void {
  const marks = (stem.match(/_{4,}/g) ?? []).length
  if (type === 'fill_in_blank' && marks !== 1) {
    throw new Error(`填空题题干必须恰好包含一处 ${BLANK_MARK} 作为空格`)
  }
  if (type !== 'fill_in_blank' && marks > 0) {
    throw new Error(`只有填空题的题干可以包含 ${BLANK_MARK}`)
  }
}

export function assertItemScore(score: unknown): void {
  if (typeof score !== 'number' || !Number.isFinite(score) || score <= 0 || score > 1000) {
    throw new Error('分值须在 0.1 到 1000 之间')
  }
  if (Math.round(score * 10) !== score * 10) {
    throw new Error('分值最多一位小数')
  }
}

/** 一道题的编辑表单:各题型答案分字段暂存,提交时按当前题型取值 */
export interface QuestionItemForm {
  type: CourseQuestionTypeValue
  stemMarkdown: string
  options: string[]
  /** 正确选项按序号记,选项文字重复或改动时不受影响;提交时换算成选项文字 */
  singleAnswerIndex: number | null
  blankAnswer: string
  trueFalseAnswer: boolean
  analysisMarkdown: string
  score: number
}

interface QuestionItemPayload {
  type: CourseQuestionTypeValue
  stemMarkdown: string
  options: string[] | null
  answer: string | boolean
  analysisMarkdown: string
  score: number
}

export function emptyQuestionItemForm(type: CourseQuestionTypeValue = 'single_choice'): QuestionItemForm {
  return {
    type,
    stemMarkdown: '',
    options: type === 'single_choice' ? ['', '', '', ''] : [],
    singleAnswerIndex: null,
    blankAnswer: '',
    trueFalseAnswer: true,
    analysisMarkdown: '',
    score: DEFAULT_ITEM_SCORE,
  }
}

export function questionItemFormFrom(item: {
  type: CourseQuestionTypeValue
  stemMarkdown: string
  options: unknown
  answer: unknown
  analysisMarkdown: string
  score?: number
}): QuestionItemForm {
  return {
    type: item.type,
    stemMarkdown: item.stemMarkdown,
    options: Array.isArray(item.options)
      ? [...item.options]
      : item.type === 'single_choice'
        ? ['', '', '', '']
        : [],
    singleAnswerIndex:
      item.type === 'single_choice' &&
      typeof item.answer === 'string' &&
      Array.isArray(item.options) &&
      item.options.indexOf(item.answer) >= 0
        ? item.options.indexOf(item.answer)
        : null,
    blankAnswer: item.type === 'fill_in_blank' && typeof item.answer === 'string' ? item.answer : '',
    trueFalseAnswer: typeof item.answer === 'boolean' ? item.answer : true,
    analysisMarkdown: item.analysisMarkdown,
    score: item.score ?? DEFAULT_ITEM_SCORE,
  }
}

export function questionItemPayload(form: QuestionItemForm): QuestionItemPayload {
  if (!form.stemMarkdown.trim()) {
    throw new Error('请输入题干')
  }
  const options = form.type === 'single_choice' ? form.options.map((item) => item.trim()) : null
  if (form.type === 'single_choice' && form.singleAnswerIndex === null) {
    throw new Error('请选择正确选项')
  }
  const answer =
    form.type === 'single_choice'
      ? (options?.[form.singleAnswerIndex ?? -1] ?? '')
      : form.type === 'fill_in_blank'
        ? form.blankAnswer.trim()
        : form.trueFalseAnswer
  assertQuestionStem(form.type, form.stemMarkdown)
  assertQuestionAnswer(form.type, options, answer)
  assertItemScore(form.score)
  return {
    type: form.type,
    stemMarkdown: form.stemMarkdown,
    options,
    answer,
    analysisMarkdown: form.analysisMarkdown,
    score: form.score,
  }
}

export interface QuestionPaperForm {
  title: string
  timeLimitMinutes: number | null
  allowRetake: boolean
  revealAnswers: boolean
  items: QuestionItemForm[]
}

export function emptyQuestionPaperForm(): QuestionPaperForm {
  return { title: '', timeLimitMinutes: null, allowRetake: true, revealAnswers: true, items: [] }
}

export function questionPaperFormFrom(detail: {
  title: string
  timeLimitMinutes: number | null
  allowRetake: boolean
  revealAnswers: boolean
  items: Parameters<typeof questionItemFormFrom>[0][]
}): QuestionPaperForm {
  return {
    title: detail.title,
    timeLimitMinutes: detail.timeLimitMinutes,
    allowRetake: detail.allowRetake,
    revealAnswers: detail.revealAnswers,
    items: detail.items.map(questionItemFormFrom),
  }
}

interface QuestionPaperPayload {
  title: string
  timeLimitMinutes: number | null
  allowRetake: boolean
  revealAnswers: boolean
  items: QuestionItemPayload[]
}

export function questionPaperPayload(form: QuestionPaperForm): QuestionPaperPayload {
  const title = form.title.trim()
  if (!title) {
    throw new Error('请输入试题标题')
  }
  if (form.items.length === 0) {
    throw new Error('试题至少要有一道题')
  }
  if (
    form.timeLimitMinutes !== null &&
    (!Number.isInteger(form.timeLimitMinutes) || form.timeLimitMinutes < 1 || form.timeLimitMinutes > 600)
  ) {
    throw new Error('限时须为 1 到 600 之间的整数分钟')
  }
  const items = form.items.map((item, index) => {
    try {
      return questionItemPayload(item)
    } catch (error: unknown) {
      throw new Error(`第 ${index + 1} 题：${error instanceof Error ? error.message : String(error)}`, {
        cause: error,
      })
    }
  })
  return {
    title,
    timeLimitMinutes: form.timeLimitMinutes,
    allowRetake: form.allowRetake,
    revealAnswers: form.revealAnswers,
    items,
  }
}

export function paperTotalScore(items: { score: number }[]): number {
  return (
    Math.round(items.reduce((sum, item) => sum + (Number.isFinite(item.score) ? item.score : 0), 0) * 10) / 10
  )
}

export function formatQuestionAnswer(
  type: CourseQuestionTypeValue,
  options: unknown,
  answer: unknown,
): string {
  assertQuestionAnswer(type, options, answer)
  const optionValues = readQuestionOptions(type, options)
  if (type === 'single_choice') {
    const value = answer as string
    return `${optionLetter(optionValues.indexOf(value))}. ${value}`
  }
  if (type === 'fill_in_blank') return answer as string
  return answer ? '正确' : '错误'
}

/** 学生作答的展示文本:空值按「未作答」显示;作答体由服务端按题型校验,不合法的值直接抛错 */
export function formatGivenAnswer(type: CourseQuestionTypeValue, options: unknown, given: unknown): string {
  if (given === null || given === undefined || given === '') return '未作答'
  return formatQuestionAnswer(type, options, given)
}
