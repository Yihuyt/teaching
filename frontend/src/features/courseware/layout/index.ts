export { DEFAULT_THEME } from '@/features/courseware/layout/theme.ts'
export type { ThemeTokens } from '@/features/courseware/layout/theme.ts'

export type { Frame, TitleFrame, PositionedScene, OverflowState } from '@/features/courseware/layout/types.ts'

export { getPreset } from '@/features/courseware/layout/presets.ts'
export type { PresetDef, Region } from '@/features/courseware/layout/presets.ts'

export { charWidthEm, textWidthEm, stripInline, wrapText, countLines, textHeight } from '@/features/courseware/layout/measure.ts'

export { measureLeafBlock } from '@/features/courseware/layout/measureBlock.ts'
export type { MeasuredHeight } from '@/features/courseware/layout/measureBlock.ts'

export { layoutScene, describeOverflow } from '@/features/courseware/layout/layout.ts'
