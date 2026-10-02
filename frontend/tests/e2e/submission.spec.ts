import { expect, test } from './fixture'

const studentSession = {
  id: 9,
  username: 'student01',
  role: 'student',
  credentialState: 'active',
  displayName: '学生一',
  enabled: true,
  mustResetPassword: false,
  createdAt: '2026-07-23T00:00:00Z',
  updatedAt: '2026-07-23T00:00:00Z',
}

const problem = {
  problem: {
    id: 12,
    courseId: 3,
    ownerId: 5,
    title: '两数之和',
    difficulty: 'easy',
    timeLimitMs: 1000,
    memoryLimitMb: 256,
    outputLimitKb: 64,
    languages: ['CPP20'],
    createdAt: '2026-07-23T00:00:00Z',
    updatedAt: '2026-07-23T00:00:00Z',
  },
  statementMarkdown: '计算两个整数之和。',
  samples: [],
  provenance: null,
}

test('编程提交的缺失指标明确显示为未记录', async ({ page }) => {
  await page.route('**/api/v1/session', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(studentSession) })
  })

  const summary = {
    id: 71,
    problemId: 12,
    accountId: 9,
    language: 'CPP20',
    status: 'ACCEPTED',
    timeUsedMs: null,
    memoryUsedKb: null,
    score: null,
    resultDetail: null,
    submittedAt: '2025-07-23T01:00:00Z',
    completedAt: '2025-07-23T01:01:00Z',
  }

  await page.route('**/api/v1/courses/3/programming-problems/12', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(problem) })
  })
  await page.route('**/api/v1/courses/3/programming-problems/12/submissions', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([summary]) })
  })
  await page.route('**/api/v1/courses/3/programming-problems/12/submissions/71', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        submission: {
          ...summary,
          problemTitle: '两数之和',
          courseId: 3,
          sourceCode: '#include <iostream>\\nint main() { return 0; }',
        },
        cases: [
          {
            caseId: 'secret/case-001',
            status: 'ACCEPTED',
            timeUsedMs: 5,
            memoryUsedKb: 1024,
            score: 100,
            detail: null,
          },
        ],
      }),
    })
  })

  await page.goto('/courses/3/problems/12')
  await expect(page.getByRole('heading', { name: '我的提交' })).toBeVisible()
  await expect(page.locator('tbody').getByText('C++ 20')).toBeVisible()
  await expect(page.getByText('71', { exact: true })).toHaveCount(0)
  await expect(page.getByText('CPP20')).toHaveCount(0)
  await page.getByRole('link', { name: '详情' }).click()

  await expect(page).toHaveURL(/\/courses\/3\/problems\/12\/submissions\/71$/)
  await expect(page.getByText('未记录')).toHaveCount(3)
  await expect(page.getByText('C++ 20')).toBeVisible()
  await expect(page.getByRole('heading', { name: '两数之和' })).toBeVisible()
  await expect(page.getByRole('link', { name: '返回题目' })).toHaveAttribute('href', '/courses/3/problems/12')
  await expect(page.getByRole('button', { name: '重判' })).toHaveCount(0)
  await expect(page.getByText('提交 71', { exact: true })).toHaveCount(0)
  await expect(page.getByText('测试点 1', { exact: true })).toBeVisible()
  await expect(page.getByText('secret/case-001', { exact: true })).toHaveCount(0)
})

test('评测说明在课程编程题页内展开', async ({ page }) => {
  await page.route('**/api/v1/session', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(studentSession) })
  })
  await page.route('**/api/v1/courses/3/programming-problems/12', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(problem) })
  })
  await page.route('**/api/v1/courses/3/programming-problems/12/submissions', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })

  await page.goto('/courses/3/problems/12')
  await expect(page.getByText('还没有提交过')).toBeVisible()
  await expect(page.getByRole('heading', { name: '支持的编程语言' })).toHaveCount(0)
  await page.getByRole('button', { name: '评测说明' }).click()
  await expect(page.getByRole('heading', { name: '支持的编程语言' })).toBeVisible()
  await expect(page.getByText('G++ 13')).toBeVisible()
  await expect(page.getByText('程序通过了全部测试点。')).toBeVisible()
})

test('评测异常不向学习者暴露工作进程信息', async ({ page }) => {
  await page.route('**/api/v1/session', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(studentSession) })
  })
  await page.route('**/api/v1/courses/3/programming-problems/12/submissions/72', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        submission: {
          id: 72,
          problemId: 12,
          problemTitle: '两数之和',
          courseId: 3,
          accountId: 9,
          language: 'PYTHON312',
          sourceCode: 'print(1)',
          status: 'WORKER_CRASH_LIMIT',
          timeUsedMs: null,
          memoryUsedKb: null,
          score: null,
          resultDetail: 'worker crashed after retries were exhausted',
          submittedAt: '2025-07-23T01:00:00Z',
          completedAt: '2025-07-23T01:01:00Z',
        },
        cases: [
          {
            caseId: '1',
            status: 'WORKER_CRASH_LIMIT',
            timeUsedMs: 0,
            memoryUsedKb: 0,
            score: null,
            detail: 'worker retry exhausted',
          },
        ],
      }),
    })
  })

  await page.goto('/courses/3/problems/12/submissions/72')

  await expect(page.getByText('评测异常').first()).toBeVisible()
  await expect(page.getByText('Python 3.12')).toBeVisible()
  await expect(page.getByText('本次评测未能完成，请稍后重新提交；若持续出现，请联系教师。')).toBeVisible()
  await expect(page.getByText(/worker|retry|重试耗尽|工作进程/i)).toHaveCount(0)
})
