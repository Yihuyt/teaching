import type {
  CourseOutlineItemViewItemType,
  ProblemViewDifficulty,
  AccountViewRole,
  SubmissionViewStatus,
} from '@/api/generated'
import type { CourseQuestionTypeValue } from '@/features/courses/question'

export const roleLabels: Readonly<Record<AccountViewRole, string>> = {
  root: '根管理员',
  admin: '管理员',
  teacher: '教师',
  student: '学生',
}

export const questionTypeLabels: Readonly<Record<CourseQuestionTypeValue, string>> = {
  single_choice: '单选题',
  fill_in_blank: '填空题',
  true_false: '判断题',
}

export const courseOutlineItemTypeLabels: Readonly<Record<CourseOutlineItemViewItemType, string>> = {
  material: '文件',
  question: '试题',
  programming_problem: '编程题',
}

export const courseOutlineItemOpenLabels: Readonly<Record<CourseOutlineItemViewItemType, string>> = {
  material: '打开',
  question: '作答',
  programming_problem: '做题',
}

type DifficultyTagType = 'success' | 'warning' | 'danger'

export const difficultyLabels: Readonly<Record<ProblemViewDifficulty, string>> = {
  easy: '简单',
  medium: '中等',
  hard: '困难',
}

export const difficultyTagTypes: Readonly<Record<ProblemViewDifficulty, DifficultyTagType>> = {
  easy: 'success',
  medium: 'warning',
  hard: 'danger',
}

type ProgrammingLanguageCode = 'C17' | 'CPP20' | 'PYTHON312'

export const programmingLanguageLabels: Readonly<Record<ProgrammingLanguageCode, string>> = {
  C17: 'C 17',
  CPP20: 'C++ 20',
  PYTHON312: 'Python 3.12',
}

export const submissionStatusLabels: Readonly<Record<SubmissionViewStatus, string>> = {
  QUEUED: '等待评测',
  ACCEPTED: '通过',
  WRONG_ANSWER: '答案错误',
  COMPILE_ERROR: '编译错误',
  RUNTIME_ERROR: '运行错误',
  TIME_LIMIT_EXCEEDED: '超出时间限制',
  MEMORY_LIMIT_EXCEEDED: '超出内存限制',
  OUTPUT_LIMIT_EXCEEDED: '超出输出限制',
  SYSTEM_ERROR: '评测异常',
  WORKER_CRASH_LIMIT: '评测异常',
}

type SubmissionStatusTagType = 'success' | 'warning' | 'danger' | 'info'

export const submissionStatusTagTypes: Readonly<Record<SubmissionViewStatus, SubmissionStatusTagType>> = {
  QUEUED: 'info',
  ACCEPTED: 'success',
  WRONG_ANSWER: 'danger',
  COMPILE_ERROR: 'danger',
  RUNTIME_ERROR: 'danger',
  TIME_LIMIT_EXCEEDED: 'warning',
  MEMORY_LIMIT_EXCEEDED: 'warning',
  OUTPUT_LIMIT_EXCEEDED: 'warning',
  SYSTEM_ERROR: 'danger',
  WORKER_CRASH_LIMIT: 'danger',
}
