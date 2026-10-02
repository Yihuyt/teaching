import MarkdownIt from 'markdown-it'

interface MarkdownToken {
  type: string
  content: string
  children: MarkdownToken[] | null
}

const markdown = new MarkdownIt({
  html: false,
  linkify: false,
  typographer: false,
})

function inlineText(tokens: MarkdownToken[]): string {
  return tokens
    .map((token) => {
      if (token.type === 'text' || token.type === 'code_inline' || token.type === 'image') {
        return token.content
      }
      if (token.type === 'softbreak' || token.type === 'hardbreak') {
        return ' '
      }
      return token.children ? inlineText(token.children) : ''
    })
    .join('')
}

export function markdownToPlainText(source: string): string {
  const blocks = (markdown.parse(source, {}) as MarkdownToken[])
    .map((token) => {
      if (token.type === 'inline' && token.children) {
        return inlineText(token.children)
      }
      if (token.type === 'fence' || token.type === 'code_block') {
        return token.content
      }
      return ''
    })
    .filter(Boolean)

  return blocks.join(' ').replace(/\s+/gu, ' ').trim()
}

export function plainTextExcerpt(source: string, maxLength = 120): string {
  if (!Number.isSafeInteger(maxLength) || maxLength <= 0) {
    throw new RangeError('摘要长度必须是正整数')
  }

  const text = markdownToPlainText(source)
  const characters = [...text]
  if (characters.length <= maxLength) {
    return text
  }
  return `${characters.slice(0, maxLength).join('').trimEnd()}…`
}
