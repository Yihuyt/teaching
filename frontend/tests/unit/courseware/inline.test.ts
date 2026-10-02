import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { parseInline, stripInline } from '@/features/courseware/inline'

/**
 * 行内语法跨语言金样:与 Java InlineMarkupGoldenTest 共同断言同一份
 * cases.json,锁定两端解析完全一致(真源是本侧 parseInline,dump 脚本重新生成)。
 */
const goldenPath = resolve(process.cwd(), '../backend/server/src/test/resources/inline-golden/cases.json')

interface GoldenCase {
  input: string
  segments: { kind: 'text' | 'bold' | 'latex'; text: string }[]
}

const cases = (JSON.parse(readFileSync(goldenPath, 'utf8')) as { cases: GoldenCase[] }).cases

describe('parseInline 金样', () => {
  it('用例数量足够', () => {
    expect(cases.length).toBeGreaterThanOrEqual(15)
  })

  for (const c of cases) {
    it(`解析:${c.input || '(空串)'}`, () => {
      const actual = parseInline(c.input).map((seg) =>
        seg.kind === 'latex' ? { kind: 'latex', text: seg.latex } : { kind: seg.kind, text: seg.text },
      )
      expect(actual).toEqual(c.segments)
    })
  }
})

describe('stripInline', () => {
  it('去标记保留公式体', () => {
    expect(stripInline('**加粗** 与 $F=ma$')).toBe('加粗 与 F=ma')
  })
})
