import {
  difficultyLabels,
  difficultyTagTypes,
  programmingLanguageLabels,
  submissionStatusLabels,
  submissionStatusTagTypes,
} from '@/shared/labels'

describe('编程题难度展示', () => {
  it('为全部难度提供明确的中文名称与标签类型', () => {
    expect(difficultyLabels).toEqual({
      easy: '简单',
      medium: '中等',
      hard: '困难',
    })
    expect(difficultyTagTypes).toEqual({
      easy: 'success',
      medium: 'warning',
      hard: 'danger',
    })
  })

  it('使用面向学习者的语言名称和评测状态', () => {
    expect(programmingLanguageLabels).toEqual({
      C17: 'C 17',
      CPP20: 'C++ 20',
      PYTHON312: 'Python 3.12',
    })
    expect(submissionStatusLabels.WORKER_CRASH_LIMIT).toBe('评测异常')
    expect(Object.values(submissionStatusLabels).join('')).not.toContain('工作进程')
    expect(Object.values(submissionStatusLabels).join('')).not.toContain('重试耗尽')
    expect(submissionStatusTagTypes).toEqual({
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
    })
  })
})
