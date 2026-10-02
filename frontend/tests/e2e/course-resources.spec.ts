import { expect, type Page } from '@playwright/test'

import { test } from './fixture'

const course = {
  id: 31,
  ownerId: 5,
  joinCode: 'A1B2C3D4E5',
  title: '软件工程',
  descriptionMarkdown: '',
  published: false,
  createdAt: '2026-07-23T00:00:00Z',
  updatedAt: '2026-07-23T00:00:00Z',
}

const folder = {
  id: 80,
  courseId: 31,
  parentId: null,
  name: '第一章资料',
  kind: 'folder',
  contentType: null,
  sizeBytes: null,
  sha256: null,
  state: 'active',
  createdAt: '2026-07-23T00:00:00Z',
  updatedAt: '2026-07-23T00:00:00Z',
}

const activeFile = {
  id: 81,
  courseId: 31,
  parentId: null,
  name: '课程讲义.pdf',
  kind: 'file',
  contentType: 'application/pdf',
  sizeBytes: 2048,
  sha256: 'a'.repeat(64),
  state: 'active',
  createdAt: '2026-07-23T00:00:00Z',
  updatedAt: '2026-07-23T00:00:00Z',
}

const pendingFile = {
  id: 82,
  courseId: 31,
  parentId: null,
  name: '上传未完成的讲义.pdf',
  kind: 'file',
  contentType: 'application/pdf',
  sizeBytes: 4096,
  sha256: 'b'.repeat(64),
  state: 'pending_upload',
  createdAt: '2026-07-23T00:00:00Z',
  updatedAt: '2026-07-23T00:00:00Z',
}

const folderChild = {
  id: 83,
  courseId: 31,
  parentId: 80,
  name: '第一章课件.pptx',
  kind: 'file',
  contentType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  sizeBytes: 8192,
  sha256: 'c'.repeat(64),
  state: 'active',
  createdAt: '2026-07-23T00:00:00Z',
  updatedAt: '2026-07-23T00:00:00Z',
}

const questions = [
  {
    id: 91,
    courseId: 31,
    title: '需求分析练习',
    timeLimitMinutes: null,
    allowRetake: true,
    revealAnswers: true,
    itemCount: 1,
    totalScore: 10,
    createdAt: '2026-07-23T00:00:00Z',
    updatedAt: '2026-07-23T00:00:00Z',
  },
]

const problems = [
  {
    id: 501,
    title: '顺序结构基础',
    difficulty: 'easy',
    testcaseConfirmed: false,
    updatedAt: '2026-07-23T00:00:00Z',
  },
  {
    id: 502,
    title: '循环结构练习',
    difficulty: 'medium',
    testcaseConfirmed: true,
    updatedAt: '2026-07-23T00:00:00Z',
  },
]

async function fulfillJson(route: Parameters<Parameters<Page['route']>[1]>[0], body: unknown, status = 200) {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

async function stubCourseManagement(page: Page): Promise<void> {
  await page.route('**/api/v1/session', (route) =>
    fulfillJson(route, {
      id: 5,
      username: 'teacher01',
      role: 'teacher',
      credentialState: 'active',
      displayName: '教师一',
      enabled: true,
      mustResetPassword: false,
      createdAt: '2026-07-23T00:00:00Z',
      updatedAt: '2026-07-23T00:00:00Z',
    }),
  )
  await page.route('**/api/v1/session/csrf', (route) =>
    fulfillJson(route, { headerName: 'X-CSRF-TOKEN', token: 'csrf-token', parameterName: '_csrf' }),
  )
  await page.route('**/api/v1/courses/31/management', (route) => fulfillJson(route, course))
  await page.route('**/api/v1/courses/31/questions', (route) => fulfillJson(route, questions))
  await page.route('**/api/v1/courses/31/programming-problems', (route) => fulfillJson(route, problems))
}

async function stubMaterials(page: Page): Promise<void> {
  await page.route(/\/api\/v1\/courses\/31\/materials(\?.*)?$/, async (route) => {
    if (route.request().method() !== 'GET') {
      await route.fallback()
      return
    }
    const parentId = new URL(route.request().url()).searchParams.get('parentId')
    await fulfillJson(route, parentId === '80' ? [folderChild] : [folder, activeFile, pendingFile])
  })
}

function rows(page: Page) {
  return page.locator('.library-table .el-table__row')
}

test('资料库根目录把文件夹、文件、试题、编程题混排在一张表里', async ({ page }) => {
  await stubCourseManagement(page)
  await stubMaterials(page)

  await page.goto('/admin/courses/31')
  await expect(page.getByRole('tab', { name: '资料库' })).toHaveAttribute('aria-selected', 'true')

  const tableRows = rows(page)
  await expect(tableRows).toHaveCount(6)
  await expect(tableRows.nth(0)).toContainText('第一章资料')
  await expect(tableRows.nth(0)).toContainText('文件夹')
  await expect(tableRows.nth(1)).toContainText('课程讲义.pdf')
  await expect(tableRows.nth(1)).toContainText('文件')
  await expect(tableRows.nth(2)).toContainText('上传未完成的讲义.pdf')
  await expect(tableRows.nth(2).getByText('上传中', { exact: true })).toBeVisible()
  await expect(tableRows.nth(3)).toContainText('需求分析练习')
  await expect(tableRows.nth(3)).toContainText('试题')
  await expect(tableRows.nth(4)).toContainText('顺序结构基础')
  await expect(tableRows.nth(4)).toContainText('编程题')
  await expect(tableRows.nth(4).getByText('未配置测试数据', { exact: true })).toBeVisible()
  await expect(tableRows.nth(5)).toContainText('循环结构练习')
  await expect(tableRows.nth(5).getByText('未配置测试数据', { exact: true })).toHaveCount(0)

  await expect(page.locator('.library-crumbs')).toHaveCount(0)
  // 成绩入口属于课程内容,资料库行不再提供
  await expect(page.getByRole('button', { name: '成绩' })).toHaveCount(0)
})

test('进入文件夹后只显示文件并出现面包屑', async ({ page }) => {
  await stubCourseManagement(page)
  await stubMaterials(page)

  await page.goto('/admin/courses/31')
  await page.getByRole('button', { name: '第一章资料' }).click()

  const crumbs = page.locator('.library-crumbs')
  await expect(crumbs).toBeVisible()
  await expect(crumbs).toContainText('资料库')
  await expect(crumbs).toContainText('第一章资料')

  const tableRows = rows(page)
  await expect(tableRows).toHaveCount(1)
  await expect(tableRows.nth(0)).toContainText('第一章课件.pptx')
  await expect(page.getByText('需求分析练习')).toHaveCount(0)
  await expect(page.getByText('顺序结构基础')).toHaveCount(0)

  await crumbs.getByRole('button', { name: '资料库' }).click()
  await expect(page.locator('.library-crumbs')).toHaveCount(0)
  await expect(rows(page)).toHaveCount(6)
})

test('关键字在本地同时过滤文件、试题与编程题', async ({ page }) => {
  await stubCourseManagement(page)
  await stubMaterials(page)
  const listRequests: string[] = []
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/courses/31/')) listRequests.push(request.url())
  })

  await page.goto('/admin/courses/31')
  await expect(rows(page)).toHaveCount(6)
  const requestsBeforeSearch = listRequests.length

  const search = page.getByPlaceholder('搜索')
  await search.fill('练习')
  await expect(rows(page)).toHaveCount(2)
  await expect(page.getByText('需求分析练习', { exact: true })).toBeVisible()
  await expect(page.getByText('循环结构练习', { exact: true })).toBeVisible()

  await search.fill('讲义')
  await expect(rows(page)).toHaveCount(2)
  await expect(rows(page).nth(0)).toContainText('课程讲义.pdf')
  await expect(rows(page).nth(1)).toContainText('上传未完成的讲义.pdf')

  await search.fill('')
  await expect(rows(page)).toHaveCount(6)
  expect(listRequests.length).toBe(requestsBeforeSearch)
})

test('新建文件夹取消后不发请求也不报错', async ({ page }) => {
  await stubCourseManagement(page)
  await stubMaterials(page)
  let folderRequests = 0
  await page.route('**/api/v1/courses/31/materials', async (route) => {
    if (route.request().method() === 'POST') {
      folderRequests += 1
      await fulfillJson(route, folder, 201)
      return
    }
    await route.fallback()
  })

  await page.goto('/admin/courses/31')
  await expect(rows(page)).toHaveCount(6)
  await page.getByRole('button', { name: '新建文件夹' }).click()

  const box = page.locator('.el-message-box')
  await expect(box).toContainText('新建文件夹')
  await box.getByRole('button', { name: '取消' }).click()

  await expect(box).toHaveCount(0)
  await expect(page.locator('.el-message--error')).toHaveCount(0)
  expect(folderRequests).toBe(0)
})

test('删除文件确认后调用删除接口，后端 409 的 detail 显示为错误提示', async ({ page }) => {
  await stubCourseManagement(page)
  await stubMaterials(page)
  const deletions: unknown[] = []
  let associated = false
  let rejectDelete = false
  await page.route('**/api/v1/courses/31/library/deletion-impact', async (route) => {
    expect(route.request().method()).toBe('POST')
    await fulfillJson(route, { associated })
  })
  await page.route('**/api/v1/courses/31/materials/deletions', async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    deletions.push(route.request().postDataJSON())
    if (rejectDelete) {
      await fulfillJson(
        route,
        {
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          detail: '课程资料状态已变化，删除未生效',
          instance: '/api/v1/courses/31/materials/deletions',
        },
        409,
      )
      return
    }
    await route.fulfill({ status: 204 })
  })

  await page.goto('/admin/courses/31')
  const fileRow = rows(page).filter({ hasText: '课程讲义.pdf' })
  await fileRow.getByRole('button', { name: '删除' }).click()
  await expect(page.locator('.el-message-box')).toContainText('确认删除？')
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('已删除', { exact: true })).toBeVisible()
  expect(deletions).toEqual([{ ids: [81] }])

  // 有关联(在课程内容中 / 挂在图谱上 / 有作答)只换一句话,不罗列明细
  associated = true
  rejectDelete = true
  await fileRow.getByRole('button', { name: '删除' }).click()
  await expect(page.locator('.el-message-box')).toContainText('该条目和其他部分关联，确认删除？')
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('课程资料状态已变化，删除未生效', { exact: true })).toBeVisible()
  expect(deletions).toEqual([{ ids: [81] }, { ids: [81] }])
})

test('资料库勾选后可一次删除所选,混合类型分模块提交', async ({ page }) => {
  await stubCourseManagement(page)
  await stubMaterials(page)
  const calls: string[] = []
  await page.route('**/api/v1/courses/31/library/deletion-impact', async (route) => {
    const body = route.request().postDataJSON() as { materialIds: number[]; questionIds: number[] }
    calls.push(`impact:${body.materialIds.join(',')}|${body.questionIds.join(',')}`)
    await fulfillJson(route, { associated: true })
  })
  await page.route('**/api/v1/courses/31/materials/deletions', async (route) => {
    calls.push(`materials:${(route.request().postDataJSON() as { ids: number[] }).ids.join(',')}`)
    await route.fulfill({ status: 204 })
  })
  await page.route('**/api/v1/courses/31/questions/deletions', async (route) => {
    calls.push(`questions:${(route.request().postDataJSON() as { ids: number[] }).ids.join(',')}`)
    await route.fulfill({ status: 204 })
  })

  await page.goto('/admin/courses/31')
  const deleteSelected = page.getByRole('button', { name: '删除所选' })
  await expect(deleteSelected).toBeDisabled()
  await rows(page).filter({ hasText: '课程讲义.pdf' }).locator('.el-checkbox').click()
  await rows(page).filter({ hasText: '需求分析练习' }).locator('.el-checkbox').click()
  await deleteSelected.click()
  await expect(page.locator('.el-message-box')).toContainText('存在和其他部分关联的条目，确认删除？')
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('已删除', { exact: true })).toBeVisible()
  expect(calls).toEqual(['impact:81|91', 'materials:81', 'questions:91'])
})

test('上传同名文件时自动改名后上传', async ({ page }) => {
  await stubCourseManagement(page)
  await stubMaterials(page)
  const ticketNames: string[] = []
  await page.route('**/api/v1/courses/31/materials/upload-tickets', async (route) => {
    const body = route.request().postDataJSON() as { name: string }
    ticketNames.push(body.name)
    if (body.name === '课程讲义.pdf') {
      await route.fulfill({
        status: 409,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'urn:teaching:problem:domain-error',
          title: '状态冲突',
          status: 409,
          detail: '同一文件夹内已有同名文件或文件夹',
        }),
      })
      return
    }
    await fulfillJson(
      route,
      {
        materialId: 90,
        method: 'PUT',
        url: 'https://oss.invalid/upload-90',
        requiredHeaders: {
          'Content-Type': 'application/pdf',
          'x-oss-meta-sha256': 'stub',
          'x-oss-forbid-overwrite': 'true',
        },
      },
      201,
    )
  })
  await page.route('https://oss.invalid/**', (route) =>
    route.fulfill({
      status: 200,
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'PUT',
        'Access-Control-Allow-Headers': 'content-type,x-oss-meta-sha256,x-oss-forbid-overwrite',
      },
    }),
  )
  const confirmed: number[] = []
  await page.route('**/api/v1/courses/31/materials/90/upload-confirmation', async (route) => {
    confirmed.push(90)
    await fulfillJson(route, { ...activeFile, id: 90, name: '课程讲义 (2).pdf' })
  })

  await page.goto('/admin/courses/31')
  await expect(rows(page)).toHaveCount(6)
  await page.locator('.toolbar-actions input[type="file"]').setInputFiles({
    name: '课程讲义.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('pdf-bytes'),
  })

  await expect(page.getByText('“课程讲义 (2).pdf”已上传')).toBeVisible()
  expect(ticketNames).toEqual(['课程讲义.pdf', '课程讲义 (2).pdf'])
  expect(confirmed).toEqual([90])
})
