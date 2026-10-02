import { describe, expect, it } from 'vitest'
import { applyTutorEvent, emptyStreamState, type TutorEvent } from '@/features/tutor/tutorStream'

/** narration 过滤:带工具调用的轮的正文进轨迹,不调工具的轮即答案 */
describe('applyTutorEvent', () => {
  it('narration 轮正文移入轨迹,finish 轮正文即答案,来源按到达顺序汇总', () => {
    const state = emptyStreamState()
    const events: TutorEvent[] = [
      {
        type: 'seed_sources',
        sources: [
          {
            ref: 'source-1',
            kind: 'kb',
            kbId: 3,
            kbName: '库',
            documentName: '讲义',
            section: '',
            snippet: 'a',
          },
        ],
      },
      { type: 'round', callId: 'r1', phase: 'start', label: '探索' },
      { type: 'thinking', callId: 'r1', text: '想想' },
      { type: 'content', callId: 'r1', text: '我先查知识库。' },
      {
        type: 'tool',
        callId: 'r1',
        toolCallId: 't1',
        name: 'rag',
        phase: 'start',
        args: '{"query":"反射"}',
      },
      { type: 'round', callId: 'r1', phase: 'end', role: 'narration' },
      {
        type: 'tool',
        callId: 'r1',
        toolCallId: 't1',
        name: 'rag',
        phase: 'end',
        error: false,
        summary: '命中',
        sources: [
          {
            ref: 'source-2',
            kind: 'kb',
            kbId: 3,
            kbName: '光学知识库',
            documentName: '讲义.pdf',
            section: '3.2',
            snippet: 'b',
          },
        ],
      },
      { type: 'round', callId: 'r2', phase: 'start', label: '探索' },
      { type: 'content', callId: 'r2', text: '反射角等于入射角[source-1]。' },
      { type: 'round', callId: 'r2', phase: 'end', role: 'finish' },
      { type: 'done', assistantMessageId: 9, sources: [] },
    ]
    events.forEach((event) => applyTutorEvent(state, event))

    expect(state.answer).toBe('反射角等于入射角[source-1]。')
    expect(state.rounds).toHaveLength(2)
    expect(state.rounds[0]?.narration).toBe('我先查知识库。')
    expect(state.rounds[0]?.thinking).toBe('想想')
    expect(state.rounds[0]?.tools[0]).toMatchObject({ name: 'rag', running: false, summary: '命中' })
    expect(state.rounds[1]?.narration).toBe('')
    expect(state.done).toBe(true)
  })

  it('done 事件以服务端来源清单为准', () => {
    const state = emptyStreamState()
    applyTutorEvent(state, {
      type: 'seed_sources',
      sources: [
        { ref: 'source-1', kind: 'kb', kbId: 1, kbName: 'k', documentName: 'd', section: '', snippet: '' },
      ],
    })
    applyTutorEvent(state, { type: 'done', assistantMessageId: 1, sources: [] })
    expect(state.sources).toEqual([])
  })
})
