/**
 * 大纲 —— 生成流水线的中间产物:模型按教学需求提出,教师确认(可改标题 / 类型 / 概要 / 配图)后逐页生成。
 * 大纲不落库,页面是唯一真相;每页的 summary 与 keyPoints 是内容生成的直接依据。
 */
import type { PresetName, SceneType } from '@/features/courseware/dsl/stage.ts'
import type { WidgetOutline, WidgetType } from '@/features/courseware/dsl/widgets.ts'

/** 流水线能生成的页面类型(视频页只能由教师上传) */
export type OutlineSceneType = Exclude<SceneType, 'video'>

/** 文生图的宽高比档位,与服务端 SceneBrief.Illustration.ASPECT_RATIOS 同值 */
export const IMAGE_ASPECT_RATIOS = ['16:9', '4:3', '1:1', '3:4'] as const
export type ImageAspectRatio = (typeof IMAGE_ASPECT_RATIOS)[number]
export const IMAGE_ASPECT_RATIO_LABELS: Record<ImageAspectRatio, string> = {
  '16:9': '横图 16:9',
  '4:3': '横图 4:3',
  '1:1': '方图 1:1',
  '3:4': '竖图 3:4',
}

/** 本页要 AI 画的一张配图:画面描述 + 宽高比;只有讲解页携带,最多一张 */
export interface OutlineIllustration {
  prompt: string
  aspectRatio: ImageAspectRatio
}

export interface OutlineScene {
  title: string
  type: OutlineSceneType
  /** content 页从除 quiz 外的 5 种中选,quiz 页固定 "quiz",interactive 页固定 "standard" */
  preset: PresetName
  /** 本页要讲什么(2~3 句) */
  summary: string
  /** 本页要点:每条一句,生成内容时逐条落实;最多 8 条、每条 120 字 */
  keyPoints: string[]
  /** 交互页组件类型(四选一);非交互页不携带 */
  widgetType?: WidgetType | null
  /** 交互页组件规格(松散对象);非交互页不携带 */
  widgetOutline?: WidgetOutline | null
  /** 放到本页的素材图片(大纲事件 images 表里的短 id);只有讲解页携带 */
  imageIds?: string[] | null
  /** 本页要 AI 画的配图;只有讲解页携带 */
  illustration?: OutlineIllustration | null
}

/** 大纲事件附带的图片表:短 id → 素材包里的哪一张图 */
export interface OutlineImage {
  id: string
  materialId: number
  imageId: string
}
