/**
 * 文本测量 —— 字符类宽度表 + 断行,布局引擎的地基。
 *
 * 不依赖 DOM:宽度以 em 为单位查表估算(CJK 全角 1.0em,西文按窄/常规/宽分类),
 * 服务端与浏览器跑同一份代码,渲染组件的 CSS 行高/字体取自 theme token,
 * 从而保证"预测高度 ≈ 实际高度"。宽度系数以 Noto Sans SC 为基准校准。
 */

const NARROW_ASCII = new Set('iljftrI.,;:\'"!|()[]{}'.split(''))
const WIDE_ASCII = new Set('mwMW@%&'.split(''))

/** CJK 断行时禁止出现在行首的标点(跟随前一字符) */
const CLOSING_PUNCT = new Set('。,、;:!?)》】」』”’%…·'.split(''))

function isCjk(code: number): boolean {
  return (
    (code >= 0x2e80 && code <= 0x9fff) || // 部首/注音/CJK 统一表意
    (code >= 0x3000 && code <= 0x303f) || // CJK 标点
    (code >= 0xf900 && code <= 0xfaff) || // 兼容表意
    (code >= 0xff00 && code <= 0xff60) || // 全角形式
    (code >= 0x20000 && code <= 0x2ffff) // 扩展 B+
  )
}

export function charWidthEm(ch: string): number {
  const code = ch.codePointAt(0)
  if (code === undefined) return 0
  if (isCjk(code) || CLOSING_PUNCT.has(ch)) return 1.0
  if (ch === ' ') return 0.3
  if (NARROW_ASCII.has(ch)) return 0.35
  if (WIDE_ASCII.has(ch)) return 0.85
  if (code >= 0x30 && code <= 0x39) return 0.6 // 数字
  if (code >= 0x41 && code <= 0x5a) return 0.68 // 大写
  if (code < 0x80) return 0.52 // 其余 ASCII
  return 0.6 // 带音符拉丁字母等
}

export function textWidthEm(text: string): number {
  let w = 0
  for (const ch of text) w += charWidthEm(ch)
  return w
}

/**
 * 去除极小内联语法后的"测量文本":
 * `**加粗**` → 去星号;`$latex$` → 保留内容(近似占位)。
 */
export function stripInline(text: string): string {
  return text.replaceAll('**', '').replace(/\$([^$]+)\$/g, '$1')
}

interface Token {
  text: string
  widthEm: number
  /** 整体不可拆分(西文单词/数字串);CJK 单字与空白可在任意 token 边界断行 */
  atomic: boolean
}

function tokenize(text: string): Token[] {
  const tokens: Token[] = []
  const wordRe = /[A-Za-z0-9_@.\-/]+/y
  let i = 0
  const chars = [...text]
  while (i < chars.length) {
    const ch = chars[i]
    if (ch === undefined) break
    if (/[A-Za-z0-9]/.test(ch)) {
      wordRe.lastIndex = 0
      const rest = chars.slice(i).join('')
      const m = /^[A-Za-z0-9_@.\-/]+/.exec(rest)
      const word = m ? m[0] : ch
      tokens.push({ text: word, widthEm: textWidthEm(word), atomic: true })
      i += [...word].length
    } else {
      tokens.push({ text: ch, widthEm: charWidthEm(ch), atomic: false })
      i += 1
    }
  }
  return tokens
}

function forceSplit(token: Token, maxEm: number): Token[] {
  const parts: Token[] = []
  let current = ''
  let currentW = 0
  for (const ch of token.text) {
    const w = charWidthEm(ch)
    if (currentW + w > maxEm && current !== '') {
      parts.push({ text: current, widthEm: currentW, atomic: true })
      current = ''
      currentW = 0
    }
    current += ch
    currentW += w
  }
  if (current !== '') parts.push({ text: current, widthEm: currentW, atomic: true })
  return parts
}

/**
 * 断行:返回各行文本。规则:
 * - CJK 逐字可断,西文单词整体断行,单词超行宽时按字符硬拆;
 * - 行首禁闭合标点(该标点跟随上一行,允许上一行轻微超宽);
 * - 行首空白丢弃。
 */
export function wrapText(text: string, fontSizePx: number, maxWidthPx: number): string[] {
  const measured = stripInline(text)
  if (measured.trim() === '') return []
  const maxEm = Math.max(1, maxWidthPx / fontSizePx)

  const tokens: Token[] = []
  for (const t of tokenize(measured)) {
    if (t.atomic && t.widthEm > maxEm) tokens.push(...forceSplit(t, maxEm))
    else tokens.push(t)
  }

  const lines: string[] = []
  let line = ''
  let lineW = 0

  for (const token of tokens) {
    const isSpace = token.text.trim() === ''
    if (lineW + token.widthEm <= maxEm) {
      if (line === '' && isSpace) continue
      line += token.text
      lineW += token.widthEm
      continue
    }
    if (!isSpace && token.text.length === 1 && CLOSING_PUNCT.has(token.text)) {
      line += token.text
      lineW += token.widthEm
      continue
    }
    if (line !== '') lines.push(line)
    line = isSpace ? '' : token.text
    lineW = isSpace ? 0 : token.widthEm
  }
  if (line !== '') lines.push(line)
  return lines.length > 0 ? lines : [measured.trim()]
}

export function countLines(text: string, fontSizePx: number, maxWidthPx: number): number {
  return wrapText(text, fontSizePx, maxWidthPx).length
}

export function textHeight(text: string, fontSizePx: number, maxWidthPx: number, lineHeight: number): number {
  return countLines(text, fontSizePx, maxWidthPx) * fontSizePx * lineHeight
}
