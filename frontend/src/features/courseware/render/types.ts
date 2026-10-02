/**
 * 渲染层与宿主视图之间的判分契约。
 * 判分在服务端完成;宿主视图调用判分接口后把结果按此形状回传给画布。
 */
export interface QuizVerdict {
  correct: boolean
  /** 正确答案的选项 label(判分响应携带,用于红绿着色) */
  answer: string[]
  explanation: string
}
