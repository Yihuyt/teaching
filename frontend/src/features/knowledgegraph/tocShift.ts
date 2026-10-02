export interface TocRow {
  number: string
  title: string
  level: number
  page: number
  /** 止页:本条内容到哪页为止;不落在任何条目区间内的页不参与抽取 */
  endPage: number
}

/** 页码整体平移:识别偏移整体判错时一次修正,越界夹回 [1, pageCount] 且保持止页 ≥ 起页 */
export function shiftTocPages(rows: TocRow[], delta: number, pageCount: number): TocRow[] {
  const clamp = (value: number) => Math.min(pageCount, Math.max(1, value))
  return rows.map((row) => {
    const page = clamp(row.page + delta)
    return { ...row, page, endPage: Math.max(page, clamp(row.endPage + delta)) }
  })
}
