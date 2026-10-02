/**
 * 布局引擎的输出模型 —— 渲染端(web)与导出端(server/pptx)共同消费。
 * 所有坐标均为 1280×720 逻辑像素。
 */
import type { PresetName } from '@/features/courseware/dsl'

export interface Frame {
  blockId: string
  x: number
  y: number
  w: number
  h: number
  /** 本帧内容的字号倍率:流式块 = 页级缩字 × 块字号档;钉住块 = 块字号档 */
  fontScale: number
  /** 教师钉住的块:位置尺寸来自排版覆盖,不参与流式排版与页级缩字 */
  pinned: boolean
}

export interface TitleFrame {
  x: number
  y: number
  w: number
  h: number
  fontSize: number
  align: 'left' | 'center'
  lines: string[]
}

export type OverflowState = 'none' | 'shrunk' | 'error'

export interface PositionedScene {
  sceneId: string
  preset: PresetName
  /** 流式内容最终采用的页级字号缩放(1 / 0.92 / 0.85 / 0.78) */
  fontScale: number
  /**
   * none = 原字号放下;shrunk = 缩字后放下;
   * error = 缩到最小仍放不下,或有钉住块底边出了页面(frames 按最小档给出,内容会溢出)
   */
  overflow: OverflowState
  title: TitleFrame | null
  /**
   * 全部块的定位帧:先是各区域的流式帧(columns 容器帧 + 其子块帧都在内),再是钉住块的帧(按块顺序);
   * 渲染端画叶子帧,容器帧用于整体高亮定位
   */
  frames: Frame[]
}
