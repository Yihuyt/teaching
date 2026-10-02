/**
 * 数量边界 —— 前端(生成面板输入 / 大纲校验)的唯一数字来源;
 * 与服务端 StageGenerationService 的常量与请求注解同值。
 */
export const LIMITS = {
  sceneCountMin: 3,
  sceneCountMax: 20,
  quizCountMax: 10,
  interactiveCountMax: 5,
  /** 确认后大纲的页数范围(终审允许比提案更小) */
  outlineMinScenes: 1,
  outlineMaxScenes: 20,
  /** 配比预留:封面 1 页 + 至少 1 页讲解 */
  reservedScenes: 2,
  sceneImagesMax: 8,
  keyPointsMax: 8,
  keyPointChars: 120,
  imagePromptChars: 800,
} as const
