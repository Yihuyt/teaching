/**
 * 主题 token —— 排版引擎与渲染组件共用的唯一样式常量来源。
 *
 * 约束:渲染组件(web)的行高/字号/内边距一律引用这里的 token,
 * 不允许写自由 CSS 值,否则服务端预测高度与浏览器实际渲染会漂移。
 */

export interface ThemeTokens {
  canvas: {
    width: number
    height: number
  }
  /** 字号阶梯(px,逻辑坐标系下) */
  fontSize: {
    cover: number
    sceneTitle: number
    h1: number
    h2: number
    body: number
    small: number
    code: number
  }
  /** 全局行高倍数 */
  lineHeight: number
  /** 等宽代码行高倍数 */
  codeLineHeight: number
  /** 间距刻度(px) */
  spacing: {
    xs: number
    sm: number
    md: number
    lg: number
  }
  colors: {
    background: string
    text: string
    muted: string
    primary: string
    accent: string
    codeBackground: string
    codeText: string
    tableBorder: string
    tableHeaderBackground: string
    highlight: string
    callout: Record<'info' | 'tip' | 'warning' | 'conclusion', { border: string; background: string }>
    /** 图表系列调色板(ECharts 与 pptx 原生图表共用同一组) */
    chart: string[]
    verdict: {
      correct: string
      correctBackground: string
      wrong: string
      wrongBackground: string
    }
  }
  fontFamily: {
    body: string
    code: string
  }
}

export const DEFAULT_THEME: ThemeTokens = {
  canvas: { width: 1280, height: 720 },
  fontSize: {
    cover: 56,
    sceneTitle: 36,
    h1: 44,
    h2: 32,
    body: 24,
    small: 18,
    code: 18,
  },
  lineHeight: 1.5,
  codeLineHeight: 1.6,
  spacing: { xs: 8, sm: 16, md: 24, lg: 40 },
  colors: {
    background: '#ffffff',
    text: '#1f2329',
    muted: '#646a73',
    primary: '#2563eb',
    accent: '#f59e0b',
    codeBackground: '#f6f8fa',
    codeText: '#1f2329',
    tableBorder: '#d0d7de',
    tableHeaderBackground: '#f0f4f8',
    highlight: '#fef3c7',
    callout: {
      info: { border: '#2563eb', background: '#eff6ff' },
      tip: { border: '#16a34a', background: '#f0fdf4' },
      warning: { border: '#d97706', background: '#fffbeb' },
      conclusion: { border: '#7c3aed', background: '#f5f3ff' },
    },
    chart: ['#2563eb', '#f59e0b', '#16a34a', '#dc2626', '#7c3aed', '#0891b2'],
    verdict: {
      correct: '#16a34a',
      correctBackground: '#f0fdf4',
      wrong: '#dc2626',
      wrongBackground: '#fef2f2',
    },
  },
  fontFamily: {
    body: "'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei', sans-serif",
    code: "'JetBrains Mono', 'Cascadia Code', Consolas, monospace",
  },
}
