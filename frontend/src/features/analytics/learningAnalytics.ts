import type { NodeMasteryView, NodeMasteryViewLevel, StudentReportClassPosition } from '@/api/generated'

/** 掌握度档位的文案与标签色(与后端 MasteryCalculator.Level 一一对应) */
export const masteryLevelLabels: Readonly<Record<NodeMasteryViewLevel, string>> = {
  UNTOUCHED: '未接触',
  TOUCHED: '已接触',
  WEAK: '薄弱',
  BASIC: '基本掌握',
  PROFICIENT: '熟练',
}

type MasteryTagType = 'info' | 'warning' | 'danger' | 'primary' | 'success'

export const masteryLevelTagTypes: Readonly<Record<NodeMasteryViewLevel, MasteryTagType>> = {
  UNTOUCHED: 'info',
  TOUCHED: 'info',
  WEAK: 'danger',
  BASIC: 'warning',
  PROFICIENT: 'success',
}

export const masteryLevelColors: Readonly<Record<NodeMasteryViewLevel, string>> = {
  UNTOUCHED: '#e5e7eb',
  TOUCHED: '#cbd5e1',
  WEAK: '#dc2626',
  BASIC: '#f59e0b',
  PROFICIENT: '#16a34a',
}

export const classPositionLabels: Readonly<Record<NonNullable<StudentReportClassPosition>, string>> = {
  TOP: '班级前 30%',
  MIDDLE: '班级中游',
  BOTTOM: '班级后 30%',
}

export function percentText(ratio: number | null | undefined): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) return '—'
  if (ratio < 0 || ratio > 1) throw new Error(`比例必须在 0~1 之间：${ratio}`)
  return `${Math.round(ratio * 100)}%`
}

/** 分数 → 档位(前端展示班级平均分等没有后端档位的数值时用,阈值与后端一致) */
export function levelOfScore(score: number | null | undefined): NodeMasteryViewLevel {
  if (score === null || score === undefined) return 'UNTOUCHED'
  if (score < 0.4) return 'WEAK'
  if (score < 0.75) return 'BASIC'
  return 'PROFICIENT'
}

/**
 * 雷达图选点:优先有分数的节点,其次按档位严重程度,最多 max 个——
 * 雷达超过十来个轴就读不清,其余在表格里看。
 */
export function radarNodes(nodes: readonly NodeMasteryView[], max: number): NodeMasteryView[] {
  if (max <= 2) throw new Error('雷达图至少需要 3 个轴')
  const scored = nodes.filter((node) => node.score !== null)
  const ranked = scored.toSorted((left, right) => (left.score ?? 0) - (right.score ?? 0))
  return ranked.slice(0, max)
}
