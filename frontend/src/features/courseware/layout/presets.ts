/**
 * 布局预设 —— 每个预设 = 标题条定义 + 1~2 个内容区矩形。
 * 坐标为 1280×720 逻辑像素。
 */
import type { PresetName } from '@/features/courseware/dsl'
import type { ThemeTokens } from '@/features/courseware/layout/theme.ts'

export interface Region {
  x: number
  y: number
  w: number
  h: number
}

export interface PresetDef {
  /** null = 该预设不渲染独立标题条(如封面把标题放内容区) */
  titleBar: {
    x: number
    y: number
    w: number
    fontSize: number
    align: 'left' | 'center'
  } | null
  regions: Region[]
}

export function getPreset(name: PresetName, theme: ThemeTokens): PresetDef {
  const { width, height } = theme.canvas
  const margin = 60
  const contentW = width - margin * 2

  switch (name) {
    case 'title-cover':
      return {
        titleBar: { x: 120, y: 250, w: width - 240, fontSize: theme.fontSize.cover, align: 'center' },
        regions: [{ x: 240, y: 420, w: width - 480, h: 180 }],
      }
    case 'section-divider':
      return {
        titleBar: { x: 120, y: 280, w: width - 240, fontSize: 48, align: 'center' },
        regions: [{ x: 240, y: 410, w: width - 480, h: 150 }],
      }
    case 'standard':
      return {
        titleBar: { x: margin, y: 44, w: contentW, fontSize: theme.fontSize.sceneTitle, align: 'left' },
        regions: [{ x: margin, y: 150, w: contentW, h: height - 150 - 50 }],
      }
    case 'two-column':
      // 分栏由页面内的 columns 块承担,区域框架与 standard 相同
      return {
        titleBar: { x: margin, y: 44, w: contentW, fontSize: theme.fontSize.sceneTitle, align: 'left' },
        regions: [{ x: margin, y: 150, w: contentW, h: height - 150 - 50 }],
      }
    case 'media-right':
      return {
        titleBar: { x: margin, y: 44, w: contentW, fontSize: theme.fontSize.sceneTitle, align: 'left' },
        regions: [
          { x: margin, y: 150, w: 620, h: height - 150 - 50 },
          { x: margin + 620 + 60, y: 150, w: contentW - 620 - 60, h: height - 150 - 50 },
        ],
      }
    case 'quiz':
      return {
        titleBar: { x: margin, y: 44, w: contentW, fontSize: theme.fontSize.sceneTitle, align: 'left' },
        regions: [{ x: 100, y: 150, w: width - 200, h: height - 150 - 50 }],
      }
  }
}
