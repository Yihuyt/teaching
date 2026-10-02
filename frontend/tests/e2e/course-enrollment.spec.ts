import { expect, test } from './fixture'

const publishedCourse = {
  id: 31,
  ownerId: 5,
  title: '软件工程',
  descriptionMarkdown: '软件工程课程',
  published: true,
  createdAt: '2026-07-29T00:00:00Z',
  updatedAt: '2026-07-29T00:00:00Z',
}

test('学习用户输入课程码后加入已发布课程', async ({ page }) => {
  let joined = false

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
        createdAt: '2026-07-29T00:00:00Z',
        updatedAt: '2026-07-29T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/session/csrf', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        headerName: 'X-CSRF-TOKEN',
        token: 'csrf-token',
        parameterName: '_csrf',
      }),
    })
  })
  await page.route('**/api/v1/courses?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: joined ? [publishedCourse] : [],
        page: 1,
        size: 12,
        total: joined ? 1 : 0,
        totalPages: joined ? 1 : 0,
      }),
    })
  })
  await page.route('**/api/v1/courses/join', async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    expect(route.request().postDataJSON()).toEqual({ joinCode: 'A1B2C3D4E5' })
    joined = true
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify(publishedCourse),
    })
  })

  await page.goto('/courses')
  await page.getByPlaceholder('请输入 10 位课程码').fill('a1b2c3d4e5')
  await page.getByRole('button', { name: '加入课程' }).click()

  await expect(page.getByText('已加入课程“软件工程”')).toBeVisible()
  await expect(page.getByRole('heading', { name: '软件工程' })).toBeVisible()
})

test('课程管理者只公布课程码，不再按用户名添加成员', async ({ page }) => {
  await page.route('**/api/v1/session', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 5,
        username: 'teacher01',
        role: 'teacher',
        credentialState: 'active',
        displayName: '教师一',
        enabled: true,
        mustResetPassword: false,
        createdAt: '2026-07-29T00:00:00Z',
        updatedAt: '2026-07-29T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/courses/31/management', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ...publishedCourse,
        joinCode: 'A1B2C3D4E5',
      }),
    })
  })
  await page.route('**/api/v1/courses/31/members', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          accountId: 5,
          username: 'teacher01',
          displayName: '教师一',
          role: 'teacher',
          joinedAt: '2026-07-29T00:00:00Z',
        },
      ]),
    })
  })
  await page.route('**/api/v1/courses/31/management/outline', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], units: [] }),
    })
  })

  await page.goto('/admin/courses/31')
  await page.getByRole('tab', { name: '成员与课程码' }).click()

  await expect(page.getByText('A1B2C3D4E5', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '添加到课程' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '查找账户' })).toHaveCount(0)
})
