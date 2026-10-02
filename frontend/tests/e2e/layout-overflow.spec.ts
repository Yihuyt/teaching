import { expect, test, type Page } from './fixture'

const rootAccount = {
  id: 1,
  username: 'root',
  role: 'root',
  credentialState: 'active',
  displayName: '系统管理员',
  enabled: true,
  mustResetPassword: false,
  createdAt: '2026-07-23T00:00:00Z',
  updatedAt: '2026-07-23T00:00:00Z',
}

async function mockRootSession(page: Page): Promise<void> {
  await page.route('**/api/v1/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(rootAccount),
    })
  })
}

async function expectNoHorizontalDocumentOverflow(page: Page): Promise<void> {
  const viewport = await page.evaluate(() => ({
    innerWidth: window.innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))

  expect(viewport.scrollWidth).toBeLessThanOrEqual(viewport.innerWidth)
}

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 1200, height: 900 })
  await mockRootSession(page)
})

test('编程题详情页在 1200px 桌面视口内没有横向溢出', async ({ page }) => {
  await page.route('**/api/v1/courses/3/programming-problems/12/submissions', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })
  await page.route('**/api/v1/courses/3/programming-problems/12', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        problem: {
          id: 12,
          courseId: 3,
          ownerId: 1,
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
      }),
    })
  })

  await page.goto('/courses/3/problems/12')

  await expect(page.getByRole('heading', { name: '整数求和' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '提交代码' })).toBeVisible()
  await expectNoHorizontalDocumentOverflow(page)
})

test('管理后台平台设置页在 1200px 桌面视口内没有横向溢出', async ({ page }) => {
  await page.route('**/api/v1/platform/settings', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        siteName: '课程教学中心',
        footerText: '课程教学中心桌面教学平台',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/platform/oss-status', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        endpoint: 'https://oss-cn-hangzhou.aliyuncs.com',
        bucket: 'teaching-platform-private',
        connected: true,
        checkedAt: '2026-07-23T00:00:00Z',
        errorCode: null,
        pendingDeletionCount: 0,
        failedDeletionCount: 0,
      }),
    })
  })

  await page.goto('/admin/platform')

  await expect(page.getByRole('heading', { name: '设置', exact: true })).toBeVisible()
  await expect(page.getByText('连接正常')).toBeVisible()
  await expectNoHorizontalDocumentOverflow(page)
})

test('课程内容在 1200px 桌面视口内保持完整操作区', async ({ page }) => {
  await page.route('**/api/v1/courses/2/management', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 2,
        ownerId: 1,
        joinCode: 'A1B2C3D4E5',
        title: 'Python 程序设计与算法基础课程',
        descriptionMarkdown: '',
        published: true,
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/courses/2/management/outline', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [],
        units: [
          {
            id: 21,
            title: '程序设计基础与开发环境配置',
            position: 1,
            items: [
              {
                id: 201,
                itemType: 'programming_problem',
                contentId: 301,
                title: '变量、表达式以及标准输入输出综合练习',
                position: 1,
              },
            ],
            children: [
              {
                id: 22,
                title: '变量与数据类型',
                position: 1,
                items: [],
                children: [],
              },
            ],
          },
        ],
      }),
    })
  })

  await page.goto('/admin/courses/2?tab=outline')

  await expect(page.getByRole('heading', { name: 'Python 程序设计与算法基础课程' })).toBeVisible()
  await expect(page.getByRole('button', { name: '添加内容' })).toHaveCount(3)
  await expectNoHorizontalDocumentOverflow(page)
})
