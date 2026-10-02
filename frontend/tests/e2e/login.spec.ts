import { expect, test } from './fixture'

test('登录页只提供受控账户登录入口', async ({ page }) => {
  await page.route('**/api/v1/session', async (route) => {
    await route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        type: 'about:blank',
        title: '未登录',
        status: 401,
        detail: '当前请求没有登录会话',
      }),
    })
  })
  await page.goto('/login')

  await expect(page.getByRole('heading', { name: '登录', exact: true })).toBeVisible()
  await expect(page.getByText('课程教学中心', { exact: true })).toBeVisible()
  await expect(page.getByText('课程教学中心桌面教学平台', { exact: true })).toBeVisible()
  await expect(page).toHaveTitle('登录 - 课程教学中心')
  await expect(page.getByLabel('用户名')).toBeVisible()
  await expect(page.getByLabel('密码')).toBeVisible()
  await expect(page.getByText('没有账户请联系老师或管理员。')).toBeVisible()
  await expect(page.getByText('请使用平台分配的账户登录', { exact: true })).toHaveCount(0)
  await expect(page.getByText('学习进度')).toHaveCount(0)
  await expect(page.locator('.login-intro')).toHaveCount(0)
  await expect(page.getByRole('link', { name: /注册/ })).toHaveCount(0)
})

test('已有登录会话访问登录页时直接进入首页', async ({ page }) => {
  await page.route('**/api/v1/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 9,
        username: 'student01',
        role: 'student',
        credentialState: 'active',
        displayName: '学生一',
        enabled: true,
        mustResetPassword: false,
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/courses?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [],
        page: 1,
        size: 6,
        total: 0,
        totalPages: 0,
      }),
    })
  })
  await page.route('**/api/v1/notifications/announcements?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [],
        page: 1,
        size: 5,
        total: 0,
        totalPages: 0,
      }),
    })
  })

  await page.goto('/login')

  await expect(page).toHaveURL(/\/home$/)
  await expect(page.getByRole('heading', { name: '我的课程', exact: true })).toBeVisible()
})
