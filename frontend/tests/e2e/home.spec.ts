import { expect, test } from './fixture'

test('首页公告摘要可以打开并阅读完整正文', async ({ page }) => {
  const contentMarkdown = [
    '**通知正文**',
    '本周课程安排已经调整，请同学们按照新的时间和教室参加课程。'.repeat(4),
    '重要结尾：请携带实验报告。',
  ].join('\n\n')

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
      body: JSON.stringify({ items: [], page: 1, size: 6, total: 0, totalPages: 0 }),
    })
  })
  await page.route('**/api/v1/notifications/announcements?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: 21,
            courseId: null,
            ownerId: 1,
            title: '课程安排调整',
            contentMarkdown,
            createdAt: '2026-08-01T00:00:00Z',
            updatedAt: '2026-08-01T00:00:00Z',
          },
        ],
        page: 1,
        size: 5,
        total: 1,
        totalPages: 1,
      }),
    })
  })

  await page.goto('/home')

  await expect(page.getByRole('heading', { name: '首页', exact: true })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '我的课程', exact: true })).toBeVisible()
  await expect(page.getByText('课程安排调整', { exact: true })).toBeVisible()
  await expect(page.getByText('重要结尾：请携带实验报告。', { exact: true })).toHaveCount(0)

  await page.getByRole('button', { name: '查看全文' }).click()

  const dialog = page.getByRole('dialog', { name: '课程安排调整' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('通知正文', { exact: true })).toBeVisible()
  await expect(dialog.getByText('重要结尾：请携带实验报告。', { exact: true })).toBeVisible()

  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).toBeHidden()
})
