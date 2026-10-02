import { describe, expect, it } from 'vitest'

import type { OutlineScene } from '@/features/courseware/dsl'
import {
  addScene,
  countScenes,
  deriveTotalScenes,
  moveScene,
  removeLastScene,
  validateCounts,
  validateOutline,
  withSceneType,
} from '@/features/courseware/outlineEdit'

const outline: OutlineScene[] = [
  { title: '封面', type: 'content', preset: 'title-cover', summary: '课程封面', keyPoints: [] },
  { title: '讲解一', type: 'content', preset: 'standard', summary: '概念', keyPoints: [], imageIds: ['img_1'] },
  { title: '测验', type: 'quiz', preset: 'quiz', summary: '小测', keyPoints: [] },
  {
    title: '仿真',
    type: 'interactive',
    preset: 'standard',
    summary: '实验',
    keyPoints: [],
    widgetType: 'simulation',
    widgetOutline: {},
  },
]

describe('countScenes', () => {
  it('按类型统计', () => {
    expect(countScenes(outline)).toEqual({ content: 2, quiz: 1, interactive: 1 })
  })
})

describe('addScene', () => {
  it('测验插到最后一个讲解页之后', () => {
    const next = addScene(outline, 'quiz')
    expect(next).toHaveLength(5)
    expect(next[2]!.type).toBe('quiz')
    expect(next[2]!.preset).toBe('quiz')
  })

  it('讲解与交互追加到末尾,原数组不被修改;手加交互页默认参数仿真', () => {
    const next = addScene(outline, 'interactive')
    expect(next[4]!.type).toBe('interactive')
    expect(next[4]!.widgetType).toBe('simulation')
    expect(outline).toHaveLength(4)
  })

  it('无讲解页时测验追加到末尾,不插到封面之前', () => {
    const onlyQuiz: OutlineScene[] = [{ title: '测验一', type: 'quiz', preset: 'quiz', summary: '小测', keyPoints: [] }]
    expect(addScene(onlyQuiz, 'quiz')[1]!.type).toBe('quiz')
  })
})

describe('removeLastScene', () => {
  it('删除该类型最后一页;类型不存在时原样返回', () => {
    const next = removeLastScene(outline, 'content')
    expect(next).toHaveLength(3)
    expect(next.filter((s) => s.type === 'content')).toHaveLength(1)
    expect(next[0]!.title).toBe('封面')

    const noQuiz = next.filter((s) => s.type !== 'quiz')
    expect(removeLastScene(noQuiz, 'quiz')).toBe(noQuiz)
  })
})

describe('moveScene', () => {
  it('上移下移;越界原样返回', () => {
    const down = moveScene(outline, 1, 1)
    expect(down[1]!.title).toBe('测验')
    expect(down[2]!.title).toBe('讲解一')
    expect(moveScene(outline, 0, -1)).toBe(outline)
    expect(moveScene(outline, 3, 1)).toBe(outline)
  })
})

describe('validateOutline', () => {
  it('标题与概要必填', () => {
    const bad: OutlineScene[] = [
      { title: '', type: 'content', preset: 'standard', summary: 'x', keyPoints: [] },
      { title: '有标题', type: 'content', preset: 'standard', summary: '  ', keyPoints: [] },
    ]
    const errors = validateOutline(bad)
    expect(errors).toHaveLength(2)
    expect(errors[0]).toContain('缺少标题')
    expect(errors[1]).toContain('缺少内容概要')
    expect(validateOutline(outline)).toEqual([])
  })

  it('交互页必须已选组件类型', () => {
    const bad: OutlineScene[] = [{ title: '实验', type: 'interactive', preset: 'standard', summary: '动手', keyPoints: [] }]
    expect(validateOutline(bad)[0]).toContain('需选择组件类型')
  })
})

describe('withSceneType', () => {
  it('切到交互补默认组件字段,切走剥除;quiz 预设固定;离开讲解页剥除配图', () => {
    const content: OutlineScene = { title: 'x', type: 'content', preset: 'media-right', summary: 's', keyPoints: [], imageIds: ['img_2'] }
    const toInteractive = withSceneType(content, 'interactive')
    expect(toInteractive.preset).toBe('standard')
    expect(toInteractive.widgetType).toBe('simulation')
    expect(toInteractive.widgetOutline).toEqual({})
    expect(toInteractive.imageIds).toBeUndefined()

    const back = withSceneType(toInteractive, 'content')
    expect(back.widgetType).toBeUndefined()
    expect(back.widgetOutline).toBeUndefined()

    expect(withSceneType(content, 'quiz').preset).toBe('quiz')
    expect(withSceneType(withSceneType(content, 'quiz'), 'content').preset).toBe('standard')
  })

  it('交互页保持类型时保留原组件配置', () => {
    const game: OutlineScene = {
      title: 'g',
      type: 'interactive',
      preset: 'standard',
      summary: 's', keyPoints: [],
      widgetType: 'game',
      widgetOutline: { gameType: 'action' },
    }
    expect(withSceneType(game, 'interactive').widgetType).toBe('game')
  })
})

describe('deriveTotalScenes', () => {
  it('总页数 = 封面 1 + 讲解 + 测验 + 交互;讲解未填返回 null', () => {
    expect(deriveTotalScenes(5, 2, 1)).toBe(9)
    expect(deriveTotalScenes(3, null, null)).toBe(4)
    expect(deriveTotalScenes(null, 2, 1)).toBeNull()
  })
})

describe('validateCounts', () => {
  it('测验 + 交互 + 封面 + 至少 1 讲解超过总页数即矛盾(与服务端同规则)', () => {
    expect(validateCounts(4, 3, 1)).toContain('配比矛盾')
    expect(validateCounts(5, 3, 1)).toContain('配比矛盾')
    expect(validateCounts(6, 3, 1)).toBeNull()
    expect(validateCounts(null, 9, 9)).toBeNull()
  })
})

describe('AI 配图', () => {
  it('离开讲解页时剥除,回到讲解页时保留', () => {
    const lesson: OutlineScene = {
      title: '讲解',
      type: 'content',
      preset: 'media-right',
      summary: '概念',
      keyPoints: [],
      illustration: { prompt: '光路示意图', aspectRatio: '1:1' },
    }
    const quiz = withSceneType(lesson, 'quiz')
    expect(quiz.illustration).toBeUndefined()
    expect(withSceneType(lesson, 'content').illustration).toEqual({ prompt: '光路示意图', aspectRatio: '1:1' })
  })

  it('要了配图却没写画面描述,生成前拦下', () => {
    const scenes: OutlineScene[] = [
      { title: '讲解', type: 'content', preset: 'standard', summary: '概念', keyPoints: [], illustration: { prompt: '  ', aspectRatio: '16:9' } },
    ]
    expect(validateOutline(scenes)).toEqual(['第 1 页「讲解」的 AI 配图缺少画面描述'])
    scenes[0]!.illustration = null
    expect(validateOutline(scenes)).toEqual([])
  })
})
