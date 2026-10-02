import { describe, it, expect } from 'vitest'
import { charWidthEm, textWidthEm, wrapText, countLines, stripInline } from '@/features/courseware/layout'

describe('charWidthEm', () => {
  it('CJK 为 1em,ASCII 分类,数字 0.6', () => {
    expect(charWidthEm('中')).toBe(1)
    expect(charWidthEm('。')).toBe(1)
    expect(charWidthEm('i')).toBe(0.35)
    expect(charWidthEm('m')).toBe(0.85)
    expect(charWidthEm('8')).toBe(0.6)
    expect(charWidthEm('a')).toBe(0.52)
    expect(charWidthEm(' ')).toBe(0.3)
  })
})

describe('stripInline', () => {
  it('去除加粗星号,保留公式内容', () => {
    expect(stripInline('牛顿**第二**定律 $F=ma$ 成立')).toBe('牛顿第二定律 F=ma 成立')
  })
})

describe('wrapText — 金标准断行', () => {
  // 24px 字号、480px 宽 → 每行 20em
  const fs = 24
  const w = 480

  it('纯 CJK:40 个汉字排 2 行', () => {
    const text = '这是一段用来测试断行的中文文本共计四十个汉字整整齐齐排成两行没有多余也没有缺少啊'
    expect(text).toHaveLength(40)
    expect(countLines(text, fs, w)).toBe(2)
  })

  it('空文本 0 行,单字 1 行', () => {
    expect(countLines('', fs, w)).toBe(0)
    expect(countLines('中', fs, w)).toBe(1)
  })

  it('中英混排:西文单词不拆', () => {
    const lines = wrapText('速度 velocity 与加速度 acceleration 是两个不同的物理量需要仔细区分', fs, w)
    for (const line of lines) {
      // 单词不应被从中间截断(每行中的字母串都应是完整单词)
      const words = line.match(/[A-Za-z]+/g) ?? []
      for (const word of words) {
        expect(['velocity', 'acceleration']).toContain(word)
      }
    }
  })

  it('超长 URL 硬拆不丢字符', () => {
    const url = 'https://example.com/very/long/path/that/never/ends/and/keeps/going/forever/and/ever'
    const lines = wrapText(url, fs, w)
    expect(lines.length).toBeGreaterThan(1)
    expect(lines.join('')).toBe(url)
  })

  it('行首不出现闭合标点', () => {
    const text = '第一句话说完了。第二句话紧跟其后,并且带着标点。第三句继续,逗号也算。结束!'
    for (const line of wrapText(text, fs, w)) {
      expect(['。', ',', '!', '?', ';']).not.toContain(line[0])
    }
  })

  it('每行宽度不超过限制(闭合标点例外)', () => {
    const text = '排版引擎必须保证每一行的估算宽度都在给定的最大宽度之内否则渲染就会溢出容器边界'
    for (const line of wrapText(text, fs, w)) {
      const em = textWidthEm(line)
      const last = line[line.length - 1] ?? ''
      const allowance = '。,、;:!?)》】」』”’%…·'.includes(last) ? 1 : 0
      expect(em).toBeLessThanOrEqual(w / fs + allowance + 1e-9)
    }
  })

  it('行数单调性:更窄的容器行数不减', () => {
    const text = '同一段文本在不同宽度的容器里排版时容器越窄行数一定不会变少这是断行算法的基本性质'
    const wide = countLines(text, fs, 800)
    const narrow = countLines(text, fs, 400)
    expect(narrow).toBeGreaterThanOrEqual(wide)
  })
})
