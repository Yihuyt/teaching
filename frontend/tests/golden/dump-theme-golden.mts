/**
 * 主题颜色金样导出:真源 DEFAULT_THEME.colors → Java 侧 ThemeColorGoldenTest 断言
 * PptxWriter 的颜色常量与之逐值一致。改主题色 → 重新生成 → 修 Java 至绿。
 * 运行:node --experimental-strip-types scripts/courseware/dump-theme-golden.mts
 */
import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { DEFAULT_THEME } from '../../src/features/courseware/layout/theme.ts'

const c = DEFAULT_THEME.colors
const out = {
  comment:
    '由 dump-theme-golden.mts 从 DEFAULT_THEME.colors 生成;Java ThemeColorGoldenTest 据此锁定 PptxWriter 颜色常量。',
  text: c.text,
  muted: c.muted,
  primary: c.primary,
  codeBackground: c.codeBackground,
  tableBorder: c.tableBorder,
  tableHeaderBackground: c.tableHeaderBackground,
  callout: c.callout,
  chart: c.chart,
}

const target = resolve(
  import.meta.dirname,
  '../../../backend/server/src/test/resources/theme-golden/colors.json',
)
writeFileSync(target, JSON.stringify(out, null, 2) + '\n')
