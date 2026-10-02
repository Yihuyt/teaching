import { describe, expect, it } from 'vitest'

import { shiftTocPages, type TocRow } from '@/features/knowledgegraph/tocShift'

const row = (page: number, endPage: number): TocRow => ({
  number: '1',
  title: '章',
  level: 1,
  page,
  endPage,
})

describe('页码整体平移', () => {
  it('起止页同步平移', () => {
    expect(shiftTocPages([row(9, 12), row(12, 16)], -8, 273)).toEqual([
      expect.objectContaining({ page: 1, endPage: 4 }),
      expect.objectContaining({ page: 4, endPage: 8 }),
    ])
  })

  it('越界夹回且保持止页不小于起页', () => {
    expect(shiftTocPages([row(2, 5)], -8, 273)).toEqual([expect.objectContaining({ page: 1, endPage: 1 })])
    expect(shiftTocPages([row(270, 273)], 10, 273)).toEqual([
      expect.objectContaining({ page: 273, endPage: 273 }),
    ])
  })
})
