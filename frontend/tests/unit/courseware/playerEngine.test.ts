import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { Stage } from '@/features/courseware/dsl'
import { createPlayerEngine, estimateDuration } from '@/features/courseware/playerEngine'

function makeStage(): Stage {
  return {
    title: '测试课件',
    theme: 'default',
    scenes: [
      {
        id: 'p1',
        type: 'content',
        title: '第一页',
        preset: 'standard',
        blocks: [
          { id: 'blk-b-1', type: 'bullets', items: [{ text: '甲' }, { text: '乙' }] },
          { id: 'blk-p-1', type: 'paragraph', text: '段落' },
        ],
        speech: [
          { text: '第一段', actions: [{ type: 'reveal', target: 'blk-b-1' }] },
          { text: '第二段', actions: [{ type: 'highlight', target: 'blk-p-1' }] },
        ],
      },
      {
        id: 'p2',
        type: 'quiz',
        title: '测验',
        preset: 'quiz',
        blocks: [
          {
            id: 'blk-q-1',
            type: 'quiz_choice',
            stem: '题干',
            options: [
              { label: 'A', text: '甲' },
              { label: 'B', text: '乙' },
            ],
            answer: [],
            multiple: false,
            explanation: '',
          },
        ],
        speech: [{ text: '请作答', actions: [] }],
      },
    ],
  }
}

describe('createPlayerEngine', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('载入后为 idle,被 reveal 引用的块初始隐藏', () => {
    const player = createPlayerEngine()
    player.load(makeStage(), {})
    expect(player.state.value).toBe('idle')
    expect(player.sceneIndex.value).toBe(0)
    expect(player.hiddenIds.value.has('blk-b-1')).toBe(true)
  })

  it('无音频段按估时推进:动作在段首触发,页尾自动翻页', () => {
    const player = createPlayerEngine()
    player.load(makeStage(), {})
    player.play()
    expect(player.state.value).toBe('playing')
    expect(player.segmentIndex.value).toBe(0)
    expect(player.hiddenIds.value.has('blk-b-1')).toBe(false)

    vi.advanceTimersByTime(estimateDuration('第一段') + 10)
    expect(player.segmentIndex.value).toBe(1)
    expect(player.highlightId.value).toBe('blk-p-1')

    vi.advanceTimersByTime(estimateDuration('第二段') + 10)
    expect(player.sceneIndex.value).toBe(1)
    expect(player.state.value).toBe('playing')
  })

  it('测验页讲稿放完后等待作答;作答后解除等待', () => {
    const player = createPlayerEngine()
    player.load(makeStage(), {})
    player.gotoScene(1, true)
    vi.advanceTimersByTime(estimateDuration('请作答') + 10)
    expect(player.state.value).toBe('waiting_quiz')

    player.submitQuiz(true)
    expect(player.state.value).toBe('paused')
    expect(player.quizAnswered.value).toBe(true)
    expect(player.quizCorrect.value).toBe(true)
  })

  it('估时下限 1.5 秒,按 4.5 字/秒推算', () => {
    expect(estimateDuration('')).toBe(1500)
    expect(estimateDuration('一二三四五六七八九')).toBe(2000)
  })
})
