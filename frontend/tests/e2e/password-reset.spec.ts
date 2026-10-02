import { expect, test } from './fixture'

test('强制改密成功后清除会话并要求重新登录', async ({ page }) => {
  let sessionRequests = 0
  await page.route('**/api/v1/session', async (route) => {
    sessionRequests += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 9,
        username: 'student01',
        role: 'student',
        credentialState: 'reset_required',
        displayName: '学生一',
        enabled: true,
        mustResetPassword: true,
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/session/csrf', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        headerName: 'X-CSRF-TOKEN',
        token: 'csrf-password-token',
        parameterName: '_csrf',
      }),
    })
  })
  await page.route('**/api/v1/accounts/me/password', async (route) => {
    expect(route.request().method()).toBe('PUT')
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-password-token')
    expect(route.request().postDataJSON()).toEqual({
      currentPassword: 'initial-pass-123',
      newPassword: 'updated-pass-456',
    })
    await route.fulfill({ status: 204 })
  })

  await page.goto('/home')
  await expect(page).toHaveURL(/\/account\/password$/)

  await page.getByLabel('当前密码').fill('initial-pass-123')
  await page.getByLabel('新密码', { exact: true }).fill('updated-pass-456')
  await page.getByLabel('确认新密码').fill('updated-pass-456')
  await page.getByRole('button', { name: '修改密码' }).click()

  await expect(page).toHaveURL(/\/login\?notice=password-changed$/)
  await expect(page.getByText('密码已修改，请重新登录', { exact: true })).toBeVisible()
  expect(sessionRequests).toBe(1)
})
