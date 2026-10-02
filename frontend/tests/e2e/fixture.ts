import { expect, test as base, type Page } from '@playwright/test'

const test = base.extend<{ platformSettings: void }>({
  platformSettings: [
    async ({ page }, use) => {
      await page.route('**/api/v1/platform/public', async (route) => {
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
      await use()
    },
    { auto: true },
  ],
})

export { expect, test, type Page }
