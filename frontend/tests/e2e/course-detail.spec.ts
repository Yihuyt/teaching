import { expect, type Page, type Route } from '@playwright/test'

import { test } from './fixture'

function noop(): void {}

async function fulfillJson(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

async function stubSession(page: Page, role = 'student'): Promise<void> {
  await page.route('**/api/v1/session', (route) =>
    fulfillJson(route, {
      id: 6,
      username: `${role}01`,
      role,
      credentialState: 'active',
      displayName: '学习用户',
      enabled: true,
      mustResetPassword: false,
      createdAt: '2026-07-23T00:00:00Z',
      updatedAt: '2026-07-23T00:00:00Z',
    }),
  )
  await page.route('**/api/v1/session/csrf', (route) =>
    fulfillJson(route, { headerName: 'X-CSRF-TOKEN', token: 'csrf-token', parameterName: '_csrf' }),
  )
}

function course(id: number, title: string) {
  return {
    id,
    ownerId: 5,
    title,
    descriptionMarkdown: '',
    published: true,
    createdAt: '2026-07-23T00:00:00Z',
    updatedAt: '2026-07-23T00:00:00Z',
  }
}

const outline = {
  items: [],
  units: [
    {
      id: 21,
      title: '第一章',
      position: 1,
      items: [
        { id: 71, itemType: 'material', contentId: 81, title: '课程讲义.pdf', position: 1 },
        { id: 72, itemType: 'question', contentId: 8, title: '第一章练习', position: 2 },
      ],
      children: [
        {
          id: 22,
          title: '循环结构',
          position: 1,
          items: [{ id: 73, itemType: 'programming_problem', contentId: 12, title: '整数求和', position: 1 }],
          children: [],
        },
      ],
    },
  ],
}

async function stubStudentCourse(page: Page): Promise<void> {
  await page.route('**/api/v1/courses/3', (route) => fulfillJson(route, course(3, '程序设计')))
  await page.route('**/api/v1/courses/3/outline', (route) => fulfillJson(route, outline))
  await page.route('**/api/v1/notifications/announcements?**', async (route) => {
    const query = new URL(route.request().url()).searchParams
    expect(query.get('courseId')).toBe('3')
    expect(query.get('management')).not.toBe('true')
    await fulfillJson(route, { items: [], page: 1, size: 100, total: 0, totalPages: 0 })
  })
  await page.route('**/api/v1/courses/3/blockcoding-config', (route) =>
    fulfillJson(route, { enabled: false }),
  )
  await page.route('**/api/v1/courses/3/knowledge-graphs', (route) => fulfillJson(route, []))
}

test('课程内容按单元列出内容并给出各类打开按钮', async ({ page }) => {
  await stubSession(page)
  await stubStudentCourse(page)

  await page.goto('/courses/3')
  await expect(page.getByRole('tab', { name: '课程内容' })).toHaveAttribute('aria-selected', 'true')

  const units = page.locator('.outline-unit')
  await expect(units).toHaveCount(2)
  await expect(units.nth(0).locator('.outline-unit__title').first()).toContainText('第一章')
  await expect(units.nth(1).locator('.outline-unit__title')).toContainText('循环结构')

  const items = page.locator('.outline-item')
  await expect(items).toHaveCount(3)
  await expect(items.nth(0)).toContainText('文件')
  await expect(items.nth(0)).toContainText('课程讲义.pdf')
  await expect(items.nth(0).getByRole('button', { name: '打开' })).toBeVisible()
  await expect(items.nth(1)).toContainText('试题')
  await expect(items.nth(1).getByRole('button', { name: '作答' })).toBeVisible()
  await expect(items.nth(2)).toContainText('编程题')
  await expect(items.nth(2).getByRole('button', { name: '做题' })).toBeVisible()
})

test('顶层内容显示在单元之前', async ({ page }) => {
  await stubSession(page)
  await stubStudentCourse(page)
  await page.route('**/api/v1/courses/3/outline', (route) =>
    fulfillJson(route, {
      items: [{ id: 70, itemType: 'material', contentId: 82, title: '课程说明.pdf', position: 1 }],
      units: outline.units,
    }),
  )

  await page.goto('/courses/3')
  await expect(page.locator('.outline-items--top .outline-item')).toContainText('课程说明.pdf')
  const items = page.locator('.outline-item')
  await expect(items).toHaveCount(4)
  await expect(items.nth(0)).toContainText('课程说明.pdf')
})

test('做题跳到课程编程题页并从课程路径加载题目', async ({ page }) => {
  await stubSession(page)
  await stubStudentCourse(page)
  let problemRequests = 0
  await page.route('**/api/v1/courses/3/programming-problems/12/submissions', (route) => fulfillJson(route, []))
  await page.route('**/api/v1/courses/3/programming-problems/12', async (route) => {
    problemRequests += 1
    await fulfillJson(route, {
      problem: {
        id: 12,
        courseId: 3,
        ownerId: 5,
        title: '整数求和',
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
    })
  })

  await page.goto('/courses/3')
  await page.getByRole('button', { name: '做题' }).click()

  await expect(page).toHaveURL(/\/courses\/3\/problems\/12$/)
  await expect(page.getByRole('heading', { name: '整数求和' })).toBeVisible()
  await expect(page.getByText('计算两个整数之和。')).toBeVisible()
  await expect(page.getByRole('link', { name: '返回课程' })).toBeVisible()
  expect(problemRequests).toBe(1)

  await page.getByRole('link', { name: '返回课程' }).click()
  await expect(page).toHaveURL(/\/courses\/3\?tab=outline$/)
  await expect(page.getByRole('tab', { name: '课程内容' })).toHaveAttribute('aria-selected', 'true')
})

test('打开文件申请下载票据', async ({ page }) => {
  await stubSession(page)
  await stubStudentCourse(page)
  let ticketRequests = 0
  await page.route('**/api/v1/courses/3/materials/81/download-tickets', async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    ticketRequests += 1
    await fulfillJson(route, { url: '/stub-download/课程讲义.pdf', expiresAt: '2026-07-23T01:00:00Z' }, 201)
  })
  await page.route('**/stub-download/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/plain', body: 'stub file' }),
  )

  await page.goto('/courses/3')
  const opened = page.waitForEvent('popup')
  await page.getByRole('button', { name: '打开' }).click()

  await expect(await opened).toHaveURL(/\/stub-download\//)
  expect(ticketRequests).toBe(1)
})

test('作答跳到课程试题页并按课程路径加载试题', async ({ page }) => {
  await stubSession(page)
  await stubStudentCourse(page)
  let questionRequests = 0
  await page.route('**/api/v1/courses/3/questions/8', async (route) => {
    questionRequests += 1
    await fulfillJson(route, {}, 500)
  })

  await page.goto('/courses/3')
  await page.getByRole('button', { name: '作答' }).click()

  await expect(page).toHaveURL(/\/courses\/3\/questions\/8$/)
  await expect.poll(() => questionRequests).toBe(1)
})

test('?tab= 与当前标签双向同步', async ({ page }) => {
  await stubSession(page)
  await stubStudentCourse(page)

  await page.goto('/courses/3?tab=announcements')
  await expect(page.getByRole('tab', { name: '课程公告' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByText('暂无课程公告')).toBeVisible()

  await page.getByRole('tab', { name: '课程内容' }).click()
  await expect(page).toHaveURL(/\/courses\/3\?tab=outline$/)
  await expect(page.getByRole('tab', { name: '课程内容' })).toHaveAttribute('aria-selected', 'true')

  await page.goto('/courses/3?tab=unknown-tab')
  await expect(page.getByRole('tab', { name: '课程内容' })).toHaveAttribute('aria-selected', 'true')
})

test('课程路由切换后迟到的旧课程响应不能覆盖当前课程', async ({ page }) => {
  await stubSession(page)

  let releaseOldCourse: () => void = noop
  let markOldCourseStarted: () => void = noop
  const oldCourseGate = new Promise<void>((resolve) => {
    releaseOldCourse = resolve
  })
  const oldCourseStarted = new Promise<void>((resolve) => {
    markOldCourseStarted = resolve
  })
  await page.route(/\/api\/v1\/courses\/(31|32)$/, async (route) => {
    const id = Number(route.request().url().split('/').at(-1))
    if (id === 31) {
      markOldCourseStarted()
      await oldCourseGate
    }
    await fulfillJson(route, course(id, id === 31 ? '迟到的旧课程' : '当前课程'))
  })
  await page.route(/\/api\/v1\/courses\/(31|32)\/outline$/, (route) =>
    fulfillJson(route, { items: [], units: [] }),
  )
  await page.route(/\/api\/v1\/courses\/(31|32)\/blockcoding-config$/, (route) =>
    fulfillJson(route, { enabled: false }),
  )
  await page.route(/\/api\/v1\/courses\/(31|32)\/knowledge-graphs$/, (route) => fulfillJson(route, []))
  await page.route('**/api/v1/notifications/announcements?**', (route) =>
    fulfillJson(route, { items: [], page: 1, size: 100, total: 0, totalPages: 0 }),
  )

  await page.goto('/courses/31')
  await oldCourseStarted
  await page.evaluate(() => {
    window.history.pushState({}, '', '/courses/32')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })

  await expect(page).toHaveURL(/\/courses\/32$/)
  await expect(page.getByText('当前课程', { exact: true })).toBeVisible()
  await expect(page.getByText('内容为空', { exact: true })).toBeVisible()
  const oldResponse = page.waitForResponse(/\/api\/v1\/courses\/31$/)
  releaseOldCourse()
  await oldResponse
  await expect(page.getByText('当前课程', { exact: true })).toBeVisible()
  await expect(page.getByText('迟到的旧课程', { exact: true })).toHaveCount(0)
})
