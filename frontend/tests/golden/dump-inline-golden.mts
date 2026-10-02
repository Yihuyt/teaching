/**
 * 行内语法金样导出:由真源 parseInline 亲自产出期望值,写入
 * backend/server/src/test/resources/inline-golden/cases.json。
 * 改语法后运行:node --experimental-strip-types scripts/courseware/dump-inline-golden.mts
 */
import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { parseInline } from '../../src/features/courseware/inline.ts'

const INPUTS = [
  '纯文本',
  '前 **加粗** 后',
  '质量 $m$ 与加速度',
  '**F = ma** 即 $F=ma$',
  '**粗1** 与 **粗2**',
  '$a$$b$',
  '$\\theta_r = \\theta_i$',
  '**a $b$ c**',
  '**a$b**c$',
  '**a*b**',
  '$$',
  '****',
  '未闭合 **加粗',
  '未闭合 $公式',
  '',
]

const cases = INPUTS.map((input) => ({
  input,
  segments: parseInline(input).map((seg) =>
    seg.kind === 'latex' ? { kind: 'latex', text: seg.latex } : { kind: seg.kind, text: seg.text },
  ),
}))

const out = {
  comment:
    '行内语法(**加粗**/$latex$)跨语言金样:由 dump-inline-golden.mts 从真源 parseInline 生成,' +
    'frontend/tests/unit/courseware/inline.test.ts 与 Java InlineMarkupGoldenTest 共同断言本文件。改语法 → 重新生成 → 修 Java 至绿。',
  cases,
}

const target = resolve(
  import.meta.dirname,
  '../../../backend/server/src/test/resources/inline-golden/cases.json',
)
writeFileSync(target, JSON.stringify(out, null, 2) + '\n')
