/**
 * 大纲编排的纯函数操作 —— 确认步骤的逻辑内核。
 * 数量由构造保证:增删直接改列表,列表里有几个就是几个。全部返回新数组,便于单测与撤销。
 */
import { LIMITS } from '@/features/courseware/dsl'
import type { OutlineScene, OutlineSceneType } from '@/features/courseware/dsl'

interface SceneCounts {
  content: number
  quiz: number
  interactive: number
}

export function countScenes(scenes: OutlineScene[]): SceneCounts {
  const counts: SceneCounts = { content: 0, quiz: 0, interactive: 0 }
  for (const scene of scenes) counts[scene.type] += 1
  return counts
}

export function addScene(scenes: OutlineScene[], type: OutlineSceneType): OutlineScene[] {
  const item: OutlineScene =
    type === 'quiz'
      ? { title: '随堂测验', type: 'quiz', preset: 'quiz', summary: '针对前文知识点的选择题测验', keyPoints: [] }
      : type === 'interactive'
        ? {
            title: '动手实验',
            type: 'interactive',
            preset: 'standard',
            summary: '与本课主题相关的交互组件',
            keyPoints: [],
            widgetType: 'simulation',
            widgetOutline: {},
          }
        : { title: '新讲解页', type: 'content', preset: 'standard', summary: '', keyPoints: [] }

  const next = [...scenes]
  if (type === 'quiz') {
    let lastContent = -1
    next.forEach((scene, i) => {
      if (scene.type === 'content') lastContent = i
    })
    // 无讲解页时追加到末尾(-1 会把测验插到封面之前)
    if (lastContent === -1) next.push(item)
    else next.splice(lastContent + 1, 0, item)
  } else {
    next.push(item)
  }
  return next
}

export function removeLastScene(scenes: OutlineScene[], type: OutlineSceneType): OutlineScene[] {
  for (let i = scenes.length - 1; i >= 0; i -= 1) {
    if (scenes[i]!.type === type) {
      const next = [...scenes]
      next.splice(i, 1)
      return next
    }
  }
  return scenes
}

export function moveScene(scenes: OutlineScene[], index: number, direction: -1 | 1): OutlineScene[] {
  const target = index + direction
  if (index < 0 || index >= scenes.length || target < 0 || target >= scenes.length) {
    return scenes
  }
  const next = [...scenes]
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item!)
  return next
}

/** 生成前校验:每页标题与概要必填(概要是逐页生成的依据),交互页必须已定组件类型,页数在范围内 */
export function validateOutline(scenes: OutlineScene[]): string[] {
  const errors: string[] = []
  if (scenes.length < LIMITS.outlineMinScenes) errors.push('大纲至少需要一页')
  if (scenes.length > LIMITS.outlineMaxScenes) errors.push(`大纲最多 ${LIMITS.outlineMaxScenes} 页`)
  scenes.forEach((scene, i) => {
    if (!scene.title.trim()) errors.push(`第 ${i + 1} 页缺少标题`)
    if (!scene.summary.trim()) errors.push(`第 ${i + 1} 页「${scene.title || '未命名'}」缺少内容概要`)
    if (scene.keyPoints.length > LIMITS.keyPointsMax) errors.push(`第 ${i + 1} 页「${scene.title || '未命名'}」的要点最多 ${LIMITS.keyPointsMax} 条`)
    if (scene.keyPoints.some((point) => point.length > LIMITS.keyPointChars))
      errors.push(`第 ${i + 1} 页「${scene.title || '未命名'}」的要点每条最多 ${LIMITS.keyPointChars} 字`)
    if (scene.type === 'interactive' && !scene.widgetType)
      errors.push(`第 ${i + 1} 页「${scene.title || '未命名'}」是交互页,需选择组件类型`)
    if (scene.illustration && !scene.illustration.prompt.trim())
      errors.push(`第 ${i + 1} 页「${scene.title || '未命名'}」的 AI 配图缺少画面描述`)
  })
  return errors
}

/**
 * 切换页面类型时的确定性归一:quiz 固定 quiz 预设、interactive 固定 standard 并补默认组件类型(simulation),
 * 离开 interactive 时剥除组件字段,离开 content 时剥除素材配图与 AI 配图(服务端拒绝带规格的非交互页与带图的非讲解页)。
 */
export function withSceneType(scene: OutlineScene, type: OutlineSceneType): OutlineScene {
  const base = { title: scene.title, summary: scene.summary, keyPoints: scene.keyPoints }
  if (type === 'quiz') return { ...base, type, preset: 'quiz' }
  if (type === 'interactive')
    return {
      ...base,
      type,
      preset: 'standard',
      widgetType: scene.widgetType ?? 'simulation',
      widgetOutline: scene.widgetOutline ?? {},
    }
  const content: OutlineScene = { ...base, type, preset: scene.preset === 'quiz' ? 'standard' : scene.preset }
  if (scene.imageIds) content.imageIds = scene.imageIds
  if (scene.illustration) content.illustration = scene.illustration
  return content
}

/**
 * 由场景数量构造总页数:封面 1 页固定 + 讲解 + 测验 + 交互。
 * 教师按场景思考,总数是结果不是输入。讲解页未指定时返回 null(总数交给模型定,测验 / 交互数仍是硬约束)。
 */
export function deriveTotalScenes(
  contentCount: number | null,
  quizCount: number | null,
  interactiveCount: number | null,
): number | null {
  if (contentCount === null) return null
  return 1 + contentCount + (quizCount ?? 0) + (interactiveCount ?? 0)
}

/** 配比矛盾检查(与服务端同规则):测验 + 交互 + 封面 + 至少 1 页讲解 ≤ 总页数 */
export function validateCounts(
  sceneCount: number | null,
  quizCount: number | null,
  interactiveCount: number | null,
): string | null {
  if (sceneCount === null) return null
  const reserved = (quizCount ?? 0) + (interactiveCount ?? 0) + LIMITS.reservedScenes
  if (reserved > sceneCount) {
    return `配比矛盾:测验 ${quizCount ?? 0} 页 + 交互 ${interactiveCount ?? 0} 页 + 封面与至少 1 页讲解,超过总页数 ${sceneCount}`
  }
  return null
}
