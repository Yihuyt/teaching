/**
 * 极小内联语法解析:`**加粗**` 与 `$latex$` → 渲染段列表。
 * 与 @/features/courseware/layout 的 stripInline 对应(测量端去标记,渲染端出样式)。
 */
type InlineSegment =
  { kind: 'text'; text: string } | { kind: 'bold'; text: string } | { kind: 'latex'; latex: string }

/**
 * 去除内联标记,返回纯文本 —— 供不能承载富文本的位置使用
 * (ECharts 图例/坐标轴、列表标题等),与测量端 stripInline 语义一致。
 */
export function stripInline(text: string): string {
  return parseInline(text)
    .map((seg) => (seg.kind === 'latex' ? seg.latex : seg.text))
    .join('')
}

export function parseInline(text: string): InlineSegment[] {
  const segments: InlineSegment[] = []
  const latexParts = text.split(/(\$[^$]+\$)/)
  for (const part of latexParts) {
    if (part === '') continue
    if (part.startsWith('$') && part.endsWith('$') && part.length > 2) {
      segments.push({ kind: 'latex', latex: part.slice(1, -1) })
      continue
    }
    const boldParts = part.split(/(\*\*[^*]+\*\*)/)
    for (const bp of boldParts) {
      if (bp === '') continue
      if (bp.startsWith('**') && bp.endsWith('**') && bp.length > 4) {
        segments.push({ kind: 'bold', text: bp.slice(2, -2) })
      } else {
        segments.push({ kind: 'text', text: bp })
      }
    }
  }
  return segments
}
