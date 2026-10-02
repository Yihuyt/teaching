import { describe, expect, it } from 'vitest'

import {
  questionItemFormFrom,
  questionItemPayload,
  emptyQuestionItemForm,
  insertBlankMark,
  assertQuestionAnswer,
  assertQuestionStem,
  formatQuestionAnswer,
  readQuestionOptions,
} from '@/features/courses/question'

describe('课程试题契约', () => {
  it('选择题只接受至少两个无重复字符串选项', () => {
    const options = ['甲', '乙', '丙']
    expect(readQuestionOptions('single_choice', options)).toEqual(options)
    expect(() => assertQuestionAnswer('single_choice', options, '乙')).not.toThrow()
    expect(() => assertQuestionAnswer('single_choice', options, 1)).toThrow('已有选项')
    expect(() => readQuestionOptions('single_choice', ['唯一选项'])).toThrow('至少需要两个选项')
    expect(() => readQuestionOptions('single_choice', ['甲', ' '])).toThrow('选项 B 不能为空')
    expect(() => readQuestionOptions('single_choice', ['甲', '乙', '甲'])).toThrow('选项 A 与 C 内容重复')
  })

  it('填空题题干恰好一处空格,答案是非空文本', () => {
    expect(() => assertQuestionStem('fill_in_blank', '光速约为 ____ m/s')).not.toThrow()
    expect(() => assertQuestionStem('fill_in_blank', '没有空格')).toThrow('恰好')
    expect(() => assertQuestionStem('fill_in_blank', '____ 与 ______')).toThrow('恰好')
    expect(() => assertQuestionStem('true_false', '含 ____ 的命题')).toThrow('只有填空题')
    expect(() => assertQuestionAnswer('fill_in_blank', null, '3e8')).not.toThrow()
    expect(() => assertQuestionAnswer('fill_in_blank', null, '  ')).toThrow('非空')
    expect(formatQuestionAnswer('fill_in_blank', null, '3e8')).toBe('3e8')
  })

  it('判断题答案使用明确类型', () => {
    expect(() => assertQuestionAnswer('true_false', null, true)).not.toThrow()
    expect(() => assertQuestionAnswer('true_false', null, 'true')).toThrow('布尔值')
  })

  it('按选项编号格式化学习页展示的标准答案', () => {
    expect(formatQuestionAnswer('single_choice', ['甲', '乙', '丙'], '丙')).toBe('C. 丙')
    expect(formatQuestionAnswer('true_false', null, false)).toBe('错误')
  })
})

describe('insertBlankMark', () => {
  it('光标处插入并与前后文用空格隔开', () => {
    expect(insertBlankMark('水的沸点是摄氏度', 5, 5)).toEqual({
      text: '水的沸点是 ____ 摄氏度',
      cursor: 11,
    })
  })

  it('已有空白时不重复补空格', () => {
    expect(insertBlankMark('答案是 。', 4, 4)).toEqual({ text: '答案是 ____ 。', cursor: 9 })
  })

  it('文首 / 文末不补多余空格', () => {
    expect(insertBlankMark('', 0, 0)).toEqual({ text: '____', cursor: 4 })
    expect(insertBlankMark('首都是 ', 4, 4)).toEqual({ text: '首都是 ____', cursor: 8 })
  })

  it('选区被替换为空格记号', () => {
    expect(insertBlankMark('首都是北京。', 3, 5)).toEqual({ text: '首都是 ____ 。', cursor: 9 })
  })
})

describe('正确选项按序号绑定', () => {
  it('选项文字重复时仍指向所选的那一项', () => {
    const form = emptyQuestionItemForm('single_choice')
    form.stemMarkdown = '下列哪项正确？'
    form.options = ['相同', '不同', '相同2', '另一个']
    form.singleAnswerIndex = 0
    expect(questionItemPayload(form).answer).toBe('相同')
  })

  it('未选择正确选项时给出明确错误', () => {
    const form = emptyQuestionItemForm('single_choice')
    form.stemMarkdown = '题干'
    form.options = ['甲', '乙']
    expect(() => questionItemPayload(form)).toThrow('请选择正确选项')
  })

  it('装配已有题目时把答案文字换算成序号', () => {
    const form = questionItemFormFrom({
      type: 'single_choice',
      stemMarkdown: '题干',
      options: ['甲', '乙', '丙'],
      answer: '丙',
      analysisMarkdown: '',
    })
    expect(form.singleAnswerIndex).toBe(2)
  })
})
