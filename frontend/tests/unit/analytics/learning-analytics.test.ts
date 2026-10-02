import { describe, expect, it } from 'vitest'

import type { NodeMasteryView } from '@/api/generated'
import { levelOfScore, percentText, radarNodes } from '@/features/analytics/learningAnalytics'

function node(id: number, score: number | null): NodeMasteryView {
  return {
    graphId: 1,
    graphName: '图谱',
    nodeId: id,
    label: `节点${id}`,
    kind: 'knowledge_point',
    score,
    level: score === null ? 'UNTOUCHED' : score < 0.4 ? 'WEAK' : 'BASIC',
    rootCause: false,
    resources: [],
  }
}

describe('学情展示辅助', () => {
  it('比例转百分比文本,无数据为破折号,越界抛错', () => {
    expect(percentText(0.456)).toBe('46%')
    expect(percentText(null)).toBe('—')
    expect(() => percentText(1.2)).toThrow('0~1')
  })

  it('分数档位阈值与后端一致', () => {
    expect(levelOfScore(null)).toBe('UNTOUCHED')
    expect(levelOfScore(0.39)).toBe('WEAK')
    expect(levelOfScore(0.74)).toBe('BASIC')
    expect(levelOfScore(0.75)).toBe('PROFICIENT')
  })

  it('雷达图优先取有分数的节点并按分数升序截断', () => {
    const picked = radarNodes([node(1, 0.9), node(2, null), node(3, 0.2), node(4, 0.5)], 3)
    expect(picked.map((item) => item.nodeId)).toEqual([3, 4, 1])
    expect(() => radarNodes([], 2)).toThrow('至少需要 3 个轴')
  })
})
