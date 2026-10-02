import { expect, type Locator, type Page, type Route } from '@playwright/test'

import { test } from './fixture'

function noop(): void {}

async function fulfillJson(route: Route, body: unknown, status = 200): Promise<void> {
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
}

async function stubSession(page: Page): Promise<void> {
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
}

function course(id = 31, title = '软件工程') {
  return {
    id,
    ownerId: 5,
    joinCode: 'A1B2C3D4E5',
    title,
    descriptionMarkdown: '',
    published: false,
    createdAt: '2026-07-23T00:00:00Z',
    updatedAt: '2026-07-23T00:00:00Z',
  }
}

type OutlineItem = { id: number; itemType: string; contentId: number; title: string; position: number }
type OutlineUnit = {
  id: number
  title: string
  position: number
  items: OutlineItem[]
  children: OutlineUnit[]
}

type Outline = { items: OutlineItem[]; units: OutlineUnit[] }

function initialOutline(): Outline {
  return {
    items: [],
    units: [
      {
        id: 11,
        title: '第一章',
        position: 1,
        items: [
          { id: 101, itemType: 'material', contentId: 81, title: '课程讲义.pdf', position: 1 },
          { id: 102, itemType: 'question', contentId: 91, title: '需求分析练习', position: 2 },
        ],
        children: [
          {
            id: 13,
            title: '需求工程',
            position: 1,
            items: [
              {
                id: 103,
                itemType: 'programming_problem',
                contentId: 502,
                title: '循环结构练习',
                position: 1,
              },
            ],
            children: [],
          },
        ],
      },
      { id: 12, title: '第二章', position: 2, items: [], children: [] },
    ],
  }
}

const materials = [
  {
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
  },
  {
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
  },
  {
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
  },
  {
    id: 84,
    courseId: 31,
    parentId: null,
    name: '实验指导.docx',
    kind: 'file',
    contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    sizeBytes: 1024,
    sha256: 'd'.repeat(64),
    state: 'active',
    createdAt: '2026-07-23T00:00:00Z',
    updatedAt: '2026-07-23T00:00:00Z',
  },
]

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
  {
    id: 503,
    title: '函数定义练习',
    difficulty: 'hard',
    testcaseConfirmed: true,
    updatedAt: '2026-07-23T00:00:00Z',
  },
]

async function stubLibrary(page: Page): Promise<void> {
  await page.route(/\/api\/v1\/courses\/31\/materials(\?.*)?$/, (route) => fulfillJson(route, materials))
  await page.route('**/api/v1/courses/31/questions', (route) => fulfillJson(route, questions))
  await page.route('**/api/v1/courses/31/programming-problems', (route) => fulfillJson(route, problems))
}

/** 管理页 + 课程内容:outline 可被用例内的 mutation 改写 */
async function stubOutlinePage(page: Page, state: { outline: Outline }): Promise<void> {
  await stubSession(page)
  await page.route('**/api/v1/courses/31/management', (route) => fulfillJson(route, course()))
  await page.route('**/api/v1/courses/31/management/outline', (route) => fulfillJson(route, state.outline))
  await stubLibrary(page)
}

function unitRow(page: Page, title: string): Locator {
  return page.locator('.unit-row').filter({ has: page.locator('strong', { hasText: title }) })
}

function itemRow(page: Page, title: string): Locator {
  return page.locator('.item-row').filter({ hasText: title })
}

/** HTML5 拖拽:源行的把手 dragstart,目标行 dragenter/dragover/drop;clientY 决定放前还是放后 */
async function dragRowTo(page: Page, source: Locator, target: Locator, after: boolean): Promise<void> {
  const dataTransfer = await page.evaluateHandle(() => new DataTransfer())
  const box = await target.boundingBox()
  if (!box) throw new Error('目标行不可见')
  const clientX = box.x + box.width / 2
  const clientY = after ? box.y + box.height - 2 : box.y + 2
  await source.locator('.drag-handle').dispatchEvent('dragstart', { dataTransfer })
  await target.dispatchEvent('dragenter', { dataTransfer, clientX, clientY })
  await target.dispatchEvent('dragover', { dataTransfer, clientX, clientY })
  await target.dispatchEvent('drop', { dataTransfer, clientX, clientY })
  await source.locator('.drag-handle').dispatchEvent('dragend', { dataTransfer })
}

test('课程内容渲染单元树与各类内容', async ({ page }) => {
  await stubOutlinePage(page, { outline: initialOutline() })

  await page.goto('/admin/courses/31?tab=outline')
  await expect(page.getByRole('tab', { name: '课程内容' })).toHaveAttribute('aria-selected', 'true')

  const tree = page.getByRole('region', { name: '课程内容' })
  await expect(tree).toBeVisible()
  await expect(unitRow(page, '第一章')).toBeVisible()
  await expect(unitRow(page, '需求工程')).toBeVisible()
  await expect(unitRow(page, '第二章')).toBeVisible()
  await expect(unitRow(page, '第二章').locator('..')).toContainText('内容为空')

  await expect(itemRow(page, '课程讲义.pdf')).toContainText('文件')
  await expect(itemRow(page, '需求分析练习')).toContainText('试题')
  await expect(itemRow(page, '循环结构练习')).toContainText('编程题')
  await expect(page.getByRole('button', { name: '添加内容' })).toHaveCount(4)
  await expect(page.getByRole('button', { name: '新建单元' })).toBeVisible()
})

test('添加内容从资料库多选后按顺序逐条加入并重新加载', async ({ page }) => {
  const state = { outline: initialOutline() }
  await stubOutlinePage(page, state)
  const added: Array<{ unitId: number; itemType: string; contentId: number }> = []
  let outlineLoads = 0
  await page.route('**/api/v1/courses/31/management/outline', async (route) => {
    outlineLoads += 1
    await fulfillJson(route, state.outline)
  })
  await page.route('**/api/v1/courses/31/outline-items', async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    const body = route.request().postDataJSON() as { unitId: number; itemType: string; contentId: number }
    added.push(body)
    const item = {
      id: 110 + added.length,
      ...body,
      title: `新增 ${added.length}`,
      position: 10 + added.length,
    }
    state.outline.units[1]!.items.push(item)
    await fulfillJson(route, item, 201)
  })

  await page.goto('/admin/courses/31?tab=outline')
  await expect(unitRow(page, '第二章')).toBeVisible()
  const loadsBeforeAdd = outlineLoads

  await unitRow(page, '第二章').getByRole('button', { name: '添加内容' }).click()
  const dialog = page.getByRole('dialog', { name: '添加内容' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('tab', { name: '资料库' })).toHaveAttribute('aria-selected', 'true')
  await expect(dialog.getByRole('tab', { name: '本地上传' })).toBeVisible()

  const rows = dialog.locator('.el-table__row')
  await expect(rows).toHaveCount(8)
  const rowOf = (title: string) => rows.filter({ hasText: title })

  await expect(rowOf('第一章资料').getByRole('checkbox')).toHaveCount(0)

  // 已在课程内容里的条目不锁定:同一内容可以再次编排
  await expect(rowOf('课程讲义.pdf').getByRole('checkbox')).not.toBeChecked()
  await expect(rowOf('课程讲义.pdf').getByRole('checkbox')).toBeEnabled()

  await expect(rowOf('上传未完成的讲义.pdf').getByRole('checkbox')).not.toBeChecked()
  await expect(rowOf('上传未完成的讲义.pdf').getByRole('checkbox')).toBeDisabled()
  await expect(rowOf('顺序结构基础').getByRole('checkbox')).not.toBeChecked()
  await expect(rowOf('顺序结构基础').getByRole('checkbox')).toBeDisabled()

  await expect(dialog.getByRole('button', { name: '添加', exact: true })).toBeDisabled()
  await expect(dialog.getByText('已选 0 项')).toBeVisible()

  await rowOf('函数定义练习').click()
  await expect(rowOf('函数定义练习').getByRole('checkbox')).toBeChecked()
  await rowOf('实验指导.docx').locator('.el-checkbox').click()
  await expect(rowOf('实验指导.docx').getByRole('checkbox')).toBeChecked()
  await expect(dialog.getByText('已选 2 项')).toBeVisible()

  await dialog.getByRole('button', { name: '添加', exact: true }).click()
  await expect(page.getByText('已添加 2 项')).toBeVisible()
  await expect(dialog).toHaveCount(0)
  expect(added).toEqual([
    { unitId: 12, itemType: 'programming_problem', contentId: 503 },
    { unitId: 12, itemType: 'material', contentId: 84 },
  ])
  expect(outlineLoads).toBe(loadsBeforeAdd + 1)
  await expect(itemRow(page, '新增 1')).toBeVisible()
  await expect(itemRow(page, '新增 2')).toBeVisible()
})

test('顶层添加内容不选单元直接加入', async ({ page }) => {
  const state = { outline: initialOutline() }
  await stubOutlinePage(page, state)
  const added: Array<{ unitId: number | null; itemType: string; contentId: number }> = []
  await page.route('**/api/v1/courses/31/outline-items', async (route) => {
    const body = route.request().postDataJSON() as {
      unitId: number | null
      itemType: string
      contentId: number
    }
    added.push(body)
    const item = {
      id: 130,
      itemType: body.itemType,
      contentId: body.contentId,
      title: '顶层附件',
      position: 1,
    }
    state.outline.items.push(item)
    await fulfillJson(route, item, 201)
  })

  await page.goto('/admin/courses/31?tab=outline')
  await expect(unitRow(page, '第一章')).toBeVisible()

  await page.locator('.manager-actions').getByRole('button', { name: '添加内容' }).click()
  const dialog = page.getByRole('dialog', { name: '添加内容' })
  await dialog.locator('.el-table__row').filter({ hasText: '实验指导.docx' }).click()
  await dialog.getByRole('button', { name: '添加', exact: true }).click()

  await expect(page.getByText('已添加 1 项')).toBeVisible()
  expect(added).toEqual([{ unitId: null, itemType: 'material', contentId: 84 }])
  await expect(page.locator('.top-item-list .item-row')).toContainText('顶层附件')
})

test('点击课程内容标题直接打开对应编辑器', async ({ page }) => {
  await stubOutlinePage(page, { outline: initialOutline() })

  await page.goto('/admin/courses/31?tab=outline')
  await itemRow(page, '需求分析练习').getByRole('button', { name: '需求分析练习', exact: true }).click()
  await expect(page).toHaveURL(/\/focus\/admin\/courses\/31\/questions\/91\/edit\?from=outline$/)

  await page.goto('/admin/courses/31?tab=outline')
  await itemRow(page, '循环结构练习').getByRole('button', { name: '循环结构练习', exact: true }).click()
  await expect(page).toHaveURL(/\/focus\/admin\/courses\/31\/programming-problems\/502\/edit\?from=outline$/)
})

test('课程内容里的试题与编程题各自打开成绩', async ({ page }) => {
  await stubOutlinePage(page, { outline: initialOutline() })
  await page.route('**/api/v1/courses/31/questions/91/results', (route) =>
    fulfillJson(route, [
      {
        accountId: 9,
        accountName: '学生甲',
        attemptCount: 2,
        bestScore: 8,
        lastSubmittedAt: '2026-07-24T00:00:00Z',
      },
    ]),
  )
  await page.route('**/api/v1/courses/31/questions/91/management', (route) =>
    fulfillJson(route, {
      id: 91,
      courseId: 31,
      title: '需求分析练习',
      timeLimitMinutes: null,
      allowRetake: true,
      revealAnswers: true,
      totalScore: 10,
      items: [],
      createdAt: '2026-07-23T00:00:00Z',
      updatedAt: '2026-07-23T00:00:00Z',
    }),
  )
  await page.route('**/api/v1/courses/31/programming-problems/502/results', (route) =>
    fulfillJson(route, [
      {
        accountId: 9,
        accountName: '学生甲',
        submissionCount: 3,
        accepted: true,
        bestScore: 100,
        lastSubmittedAt: '2026-07-24T00:00:00Z',
      },
    ]),
  )

  await page.goto('/admin/courses/31?tab=outline')
  await itemRow(page, '需求分析练习').getByRole('button', { name: '成绩' }).click()
  const questionDialog = page.getByRole('dialog', { name: '成绩 · 需求分析练习' })
  await expect(questionDialog).toContainText('学生甲')
  await expect(questionDialog).toContainText('8 / 10')
  await page.keyboard.press('Escape')
  await expect(questionDialog).toHaveCount(0)

  await itemRow(page, '循环结构练习').getByRole('button', { name: '成绩' }).click()
  const problemDialog = page.getByRole('dialog', { name: '成绩 · 循环结构练习' })
  await expect(problemDialog).toContainText('学生甲')
  await expect(problemDialog).toContainText('通过')
  await expect(problemDialog).toContainText('100')
  await expect(itemRow(page, '课程讲义.pdf').getByRole('button', { name: '成绩' })).toHaveCount(0)
})

test('移除内容确认后调用删除接口', async ({ page }) => {
  const state = { outline: initialOutline() }
  await stubOutlinePage(page, state)
  const removed: number[] = []
  await page.route('**/api/v1/courses/31/outline-items/*', async (route) => {
    expect(route.request().method()).toBe('DELETE')
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    const itemId = Number(route.request().url().split('/').at(-1))
    removed.push(itemId)
    state.outline.units[0]!.items = state.outline.units[0]!.items.filter((item) => item.id !== itemId)
    await route.fulfill({ status: 204 })
  })

  await page.goto('/admin/courses/31?tab=outline')
  await itemRow(page, '需求分析练习').getByRole('button', { name: '移除' }).click()
  await expect(page.locator('.el-message-box')).toContainText('确认移除？')
  await page.getByRole('button', { name: '取消' }).click()
  expect(removed).toEqual([])

  await itemRow(page, '需求分析练习').getByRole('button', { name: '移除' }).click()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('已移除', { exact: true })).toBeVisible()
  expect(removed).toEqual([102])
  await expect(itemRow(page, '需求分析练习')).toHaveCount(0)
})

test('新建单元与新建子单元提交到单元接口', async ({ page }) => {
  const state = { outline: initialOutline() }
  await stubOutlinePage(page, state)
  const created: Array<{ parentId: number | null; title: string }> = []
  await page.route('**/api/v1/courses/31/units', async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    const body = route.request().postDataJSON() as { parentId: number | null; title: string }
    created.push(body)
    const unit = { id: 20 + created.length, title: body.title, position: 9, items: [], children: [] }
    if (body.parentId === null) state.outline.units.push(unit)
    else state.outline.units[1]!.children.push(unit)
    await fulfillJson(route, unit, 201)
  })

  await page.goto('/admin/courses/31?tab=outline')
  await page.getByRole('button', { name: '新建单元' }).click()
  const dialog = page.getByRole('dialog', { name: '新建单元' })
  await dialog.getByLabel('单元名称').fill('第三章')
  await dialog.getByRole('button', { name: '保存' }).click()
  await expect(page.getByText('单元已创建')).toBeVisible()
  await expect(unitRow(page, '第三章')).toBeVisible()

  await unitRow(page, '第二章').getByRole('button', { name: '新建子单元' }).click()
  await page.getByRole('dialog', { name: '新建单元' }).getByLabel('单元名称').fill('设计模式')
  await page.getByRole('dialog', { name: '新建单元' }).getByRole('button', { name: '保存' }).click()
  await expect(unitRow(page, '设计模式')).toBeVisible()

  expect(created).toEqual([
    { parentId: null, title: '第三章' },
    { parentId: 12, title: '设计模式' },
  ])
})

test('拖拽调整内容顺序后提交完整课程内容顺序', async ({ page }) => {
  const state = { outline: initialOutline() }
  await stubOutlinePage(page, state)
  const orders: unknown[] = []
  await page.route('**/api/v1/courses/31/outline-order', async (route) => {
    expect(route.request().method()).toBe('PUT')
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    orders.push(route.request().postDataJSON())
    const first = state.outline.units[0]!
    first.items = [
      { ...first.items[1]!, position: 1 },
      { ...first.items[0]!, position: 2 },
    ]
    await fulfillJson(route, state.outline)
  })

  await page.goto('/admin/courses/31?tab=outline')
  await expect(itemRow(page, '需求分析练习')).toBeVisible()
  await dragRowTo(page, itemRow(page, '需求分析练习'), itemRow(page, '课程讲义.pdf'), false)

  await expect(page.getByText('内容顺序已更新')).toBeVisible()
  expect(orders).toEqual([
    {
      units: [
        { unitId: 11, parentId: null, position: 1 },
        { unitId: 13, parentId: 11, position: 1 },
        { unitId: 12, parentId: null, position: 2 },
      ],
      items: [
        { itemId: 102, unitId: 11, position: 1 },
        { itemId: 101, unitId: 11, position: 2 },
        { itemId: 103, unitId: 13, position: 1 },
      ],
    },
  ])
  const firstUnitItems = page.locator('.unit-node--root').first().locator('.item-row')
  await expect(firstUnitItems.nth(0)).toContainText('需求分析练习')
  await expect(firstUnitItems.nth(1)).toContainText('课程讲义.pdf')
})

test('拖拽调整单元顺序只在同级生效', async ({ page }) => {
  const state = { outline: initialOutline() }
  await stubOutlinePage(page, state)
  const orders: unknown[] = []
  await page.route('**/api/v1/courses/31/outline-order', async (route) => {
    orders.push(route.request().postDataJSON())
    state.outline.units = [
      { ...state.outline.units[1]!, position: 1 },
      { ...state.outline.units[0]!, position: 2 },
    ]
    await fulfillJson(route, state.outline)
  })

  await page.goto('/admin/courses/31?tab=outline')
  await expect(unitRow(page, '第二章')).toBeVisible()

  // 子单元拖到顶级单元上:不是同级,不提交
  await dragRowTo(page, unitRow(page, '需求工程'), unitRow(page, '第二章'), true)
  expect(orders).toEqual([])

  await dragRowTo(page, unitRow(page, '第二章'), unitRow(page, '第一章'), false)
  await expect(page.getByText('单元顺序已更新')).toBeVisible()
  expect(orders).toEqual([
    {
      units: [
        { unitId: 12, parentId: null, position: 1 },
        { unitId: 11, parentId: null, position: 2 },
        { unitId: 13, parentId: 11, position: 1 },
      ],
      items: [
        { itemId: 101, unitId: 11, position: 1 },
        { itemId: 102, unitId: 11, position: 2 },
        { itemId: 103, unitId: 13, position: 1 },
      ],
    },
  ])
  await expect(page.locator('.unit-node--root').first()).toContainText('第二章')
})

test('管理课程切换时关闭旧课程单元对话框并清空旧树引用', async ({ page }) => {
  await stubSession(page)
  await page.route(/\/api\/v1\/courses\/(31|32)\/management$/, async (route) => {
    const id = Number(route.request().url().split('/').at(-2))
    await fulfillJson(route, course(id, id === 31 ? '旧课程' : '新课程'))
  })
  await page.route(/\/api\/v1\/courses\/(31|32)\/management\/outline$/, async (route) => {
    const id = Number(route.request().url().split('/').at(-3))
    await fulfillJson(route, {
      items: [],
      units: [
        {
          id: id * 10 + 1,
          title: id === 31 ? '旧课程第一章' : '新课程第一章',
          position: 1,
          items: [],
          children: [
            {
              id: id * 10 + 2,
              title: id === 31 ? '旧课程子单元' : '新课程子单元',
              position: 1,
              items: [],
              children: [],
            },
          ],
        },
      ],
    })
  })

  await page.goto('/admin/courses/31?tab=outline')
  await unitRow(page, '旧课程子单元').getByRole('button', { name: '重命名' }).click()
  await expect(page.getByRole('dialog', { name: '重命名单元' })).toBeVisible()

  await page.evaluate(() => {
    window.history.pushState({}, '', '/admin/courses/32?tab=outline')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })

  await expect(page).toHaveURL(/\/admin\/courses\/32\?tab=outline$/)
  await expect(page.getByRole('dialog', { name: '重命名单元' })).toHaveCount(0)
  await expect(page.getByText('新课程第一章', { exact: true })).toBeVisible()
  await expect(page.getByText('旧课程子单元', { exact: true })).toHaveCount(0)
})

test('管理课程路由切换后迟到的旧课程响应不能覆盖当前课程', async ({ page }) => {
  await stubSession(page)

  let releaseOldCourse: () => void = noop
  let markOldCourseStarted: () => void = noop
  const oldCourseGate = new Promise<void>((resolve) => {
    releaseOldCourse = resolve
  })
  const oldCourseStarted = new Promise<void>((resolve) => {
    markOldCourseStarted = resolve
  })
  await page.route(/\/api\/v1\/courses\/(31|32)\/management$/, async (route) => {
    const id = Number(route.request().url().split('/').at(-2))
    if (id === 31) {
      markOldCourseStarted()
      await oldCourseGate
    }
    await fulfillJson(route, course(id, id === 31 ? '迟到的旧管理课程' : '当前管理课程'))
  })
  await page.route(/\/api\/v1\/courses\/(31|32)\/management\/outline$/, (route) =>
    fulfillJson(route, { items: [], units: [] }),
  )

  await page.goto('/admin/courses/31?tab=outline')
  await oldCourseStarted
  await page.evaluate(() => {
    window.history.pushState({}, '', '/admin/courses/32?tab=outline')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })

  await expect(page).toHaveURL(/\/admin\/courses\/32\?tab=outline$/)
  await expect(page.getByRole('heading', { name: '当前管理课程' })).toBeVisible()
  await expect(page.getByText('内容为空', { exact: true })).toBeVisible()
  const oldResponse = page.waitForResponse(/\/api\/v1\/courses\/31\/management$/)
  releaseOldCourse()
  await oldResponse
  await expect(page.getByRole('heading', { name: '当前管理课程' })).toBeVisible()
  await expect(page.getByText('迟到的旧管理课程', { exact: true })).toHaveCount(0)
})

test('旧课程排序响应晚到时不能覆盖新课程课程内容', async ({ page }) => {
  await stubSession(page)
  await page.route(/\/api\/v1\/courses\/(31|32)\/management$/, async (route) => {
    const id = Number(route.request().url().split('/').at(-2))
    await fulfillJson(route, course(id, id === 31 ? '旧课程' : '新课程'))
  })
  await page.route(/\/api\/v1\/courses\/(31|32)\/management\/outline$/, async (route) => {
    const id = Number(route.request().url().split('/').at(-3))
    const units =
      id === 31
        ? [
            { id: 311, title: '旧课程第一章', position: 1, children: [], items: [] },
            { id: 312, title: '旧课程第二章', position: 2, children: [], items: [] },
          ]
        : [{ id: 321, title: '新课程唯一单元', position: 1, children: [], items: [] }]
    await fulfillJson(route, { items: [], units })
  })

  let releaseOldOrder: () => void = noop
  let markOldOrderStarted: () => void = noop
  const oldOrderGate = new Promise<void>((resolve) => {
    releaseOldOrder = resolve
  })
  const oldOrderStarted = new Promise<void>((resolve) => {
    markOldOrderStarted = resolve
  })
  await page.route('**/api/v1/courses/31/outline-order', async (route) => {
    markOldOrderStarted()
    await oldOrderGate
    await fulfillJson(route, {
      items: [],
      units: [
        { id: 312, title: '旧课程第二章', position: 1, children: [], items: [] },
        { id: 311, title: '旧课程第一章', position: 2, children: [], items: [] },
      ],
    })
  })

  await page.goto('/admin/courses/31?tab=outline')
  await expect(unitRow(page, '旧课程第二章')).toBeVisible()
  await dragRowTo(page, unitRow(page, '旧课程第二章'), unitRow(page, '旧课程第一章'), false)
  await oldOrderStarted
  await page.evaluate(() => {
    window.history.pushState({}, '', '/admin/courses/32?tab=outline')
    window.dispatchEvent(new PopStateEvent('popstate'))
  })

  await expect(page.getByText('新课程唯一单元', { exact: true })).toBeVisible()
  const oldResponse = page.waitForResponse(/\/api\/v1\/courses\/31\/outline-order$/)
  releaseOldOrder()
  await oldResponse
  await expect(page.getByText('新课程唯一单元', { exact: true })).toBeVisible()
  await expect(page.getByText('旧课程第二章', { exact: true })).toHaveCount(0)
})

test('本地上传遇到资料库同名文件时自动改名后加入', async ({ page }) => {
  const state = { outline: initialOutline() }
  await stubOutlinePage(page, state)
  const added: Array<{ unitId: number | null; itemType: string; contentId: number }> = []
  await page.route('**/api/v1/courses/31/outline-items', async (route) => {
    const body = route.request().postDataJSON() as {
      unitId: number | null
      itemType: string
      contentId: number
    }
    added.push(body)
    const item = {
      id: 140,
      itemType: body.itemType,
      contentId: body.contentId,
      title: '实验指导 (2).docx',
      position: 1,
    }
    state.outline.items.push(item)
    await fulfillJson(route, item, 201)
  })
  const ticketNames: string[] = []
  await page.route('**/api/v1/courses/31/materials/upload-tickets', async (route) => {
    const body = route.request().postDataJSON() as { name: string }
    ticketNames.push(body.name)
    if (body.name === '实验指导.docx') {
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
        requiredHeaders: { 'Content-Type': 'application/octet-stream' },
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
        'Access-Control-Allow-Headers': 'content-type',
      },
    }),
  )
  await page.route('**/api/v1/courses/31/materials/90/upload-confirmation', (route) =>
    fulfillJson(route, { ok: true }),
  )

  await page.goto('/admin/courses/31?tab=outline')
  await page.locator('.manager-actions').getByRole('button', { name: '添加内容' }).click()
  const picker = page.getByRole('dialog', { name: '添加内容' })
  await picker.getByRole('tab', { name: '本地上传' }).click()
  await picker.locator('input[type="file"]').setInputFiles({
    name: '实验指导.docx',
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    buffer: Buffer.from('docx-bytes'),
  })
  await expect(picker.locator('.local-list')).toContainText('实验指导.docx')
  await picker.getByRole('button', { name: '添加', exact: true }).click()

  await expect(page.getByText('已添加 1 项')).toBeVisible()
  expect(ticketNames).toEqual(['实验指导.docx', '实验指导 (2).docx'])
  expect(added).toEqual([{ unitId: null, itemType: 'material', contentId: 90 }])
  await expect(page.locator('.top-item-list .item-row')).toContainText('实验指导 (2).docx')
})

test('条目可拖进单元标题行,也可拖回顶层落点', async ({ page }) => {
  const state = { outline: initialOutline() }
  state.outline.items.push({ id: 250, itemType: 'material', contentId: 88, title: '顶层讲义', position: 1 })
  await stubOutlinePage(page, state)
  const orders: Array<{ items: Array<{ itemId: number; unitId: number | null; position: number }> }> = []
  await page.route('**/api/v1/courses/31/outline-order', async (route) => {
    const body = route.request().postDataJSON() as (typeof orders)[number]
    orders.push(body)
    if (orders.length === 1) {
      state.outline.items = []
      state.outline.units[1]!.items = [
        { id: 250, itemType: 'material', contentId: 88, title: '顶层讲义', position: 1 },
      ]
    } else {
      state.outline.units[1]!.items = []
      state.outline.items = [{ id: 250, itemType: 'material', contentId: 88, title: '顶层讲义', position: 1 }]
    }
    await fulfillJson(route, state.outline)
  })

  await page.goto('/admin/courses/31?tab=outline')
  await expect(page.locator('.top-item-list .item-row')).toContainText('顶层讲义')

  await dragRowTo(page, itemRow(page, '顶层讲义'), unitRow(page, '第二章'), true)
  await expect(page.getByText('内容顺序已更新').first()).toBeVisible()
  expect(orders[0]!.items.find((entry) => entry.itemId === 250)).toEqual({
    itemId: 250,
    unitId: 12,
    position: 1,
  })
  await expect(page.locator('.top-item-list')).toHaveCount(0)
  await expect(itemRow(page, '顶层讲义')).toBeVisible()

  const dataTransfer = await page.evaluateHandle(() => new DataTransfer())
  await itemRow(page, '顶层讲义').locator('.drag-handle').dispatchEvent('dragstart', { dataTransfer })
  const zone = page.locator('.top-drop-zone')
  await expect(zone).toContainText('移动到顶层')
  await zone.dispatchEvent('dragenter', { dataTransfer })
  await zone.dispatchEvent('dragover', { dataTransfer })
  await zone.dispatchEvent('drop', { dataTransfer })

  await expect(page.locator('.top-item-list .item-row')).toContainText('顶层讲义')
  expect(orders[1]!.items.find((entry) => entry.itemId === 250)).toEqual({
    itemId: 250,
    unitId: null,
    position: 1,
  })
})

test('条目可拖到别的单元的条目之前（跨组条目到条目）', async ({ page }) => {
  const state = { outline: initialOutline() }
  await stubOutlinePage(page, state)
  const orders: Array<{ items: Array<{ itemId: number; unitId: number | null; position: number }> }> = []
  await page.route('**/api/v1/courses/31/outline-order', async (route) => {
    orders.push(route.request().postDataJSON() as (typeof orders)[number])
    await fulfillJson(route, state.outline)
  })

  await page.goto('/admin/courses/31?tab=outline')
  await dragRowTo(page, itemRow(page, '需求分析练习'), itemRow(page, '循环结构练习'), false)

  await expect(page.getByText('内容顺序已更新').first()).toBeVisible()
  expect(orders[0]!.items).toEqual(
    expect.arrayContaining([
      { itemId: 102, unitId: 13, position: 1 },
      { itemId: 103, unitId: 13, position: 2 },
      { itemId: 101, unitId: 11, position: 1 },
    ]),
  )
})

test('同一内容编排两处时按条目独立显示与移除', async ({ page }) => {
  const state = { outline: initialOutline() }
  state.outline.items.push({
    id: 260,
    itemType: 'material',
    contentId: 81,
    title: '课程讲义.pdf',
    position: 1,
  })
  await stubOutlinePage(page, state)
  const removed: number[] = []
  await page.route('**/api/v1/courses/31/outline-items/*', async (route) => {
    const itemId = Number(route.request().url().split('/').at(-1))
    removed.push(itemId)
    state.outline.items = state.outline.items.filter((item) => item.id !== itemId)
    await route.fulfill({ status: 204 })
  })

  await page.goto('/admin/courses/31?tab=outline')
  await expect(itemRow(page, '课程讲义.pdf')).toHaveCount(2)
  await page.locator('.top-item-list').getByRole('button', { name: '移除' }).click()
  await page.getByRole('button', { name: '确定' }).click()

  await expect(page.getByText('已移除', { exact: true })).toBeVisible()
  expect(removed).toEqual([260])
  await expect(itemRow(page, '课程讲义.pdf')).toHaveCount(1)
})

test('点击文件标题走管理下载票据', async ({ page }) => {
  await stubOutlinePage(page, { outline: initialOutline() })
  let ticketRequests = 0
  await page.route('**/api/v1/courses/31/materials/81/management/download-tickets', async (route) => {
    ticketRequests += 1
    await fulfillJson(route, { url: 'https://oss.invalid/download-81', expiresAt: '2026-07-24T00:00:00Z' })
  })
  await page.route('https://oss.invalid/**', (route) => route.fulfill({ status: 200, body: 'ok' }))

  await page.goto('/admin/courses/31?tab=outline')
  const popup = page.waitForEvent('popup')
  await itemRow(page, '课程讲义.pdf').getByRole('button', { name: '课程讲义.pdf', exact: true }).click()
  await popup
  expect(ticketRequests).toBe(1)
})

test('本地上传中途失败时已传部分先加入，对话框保留未传文件', async ({ page }) => {
  const state = { outline: initialOutline() }
  await stubOutlinePage(page, state)
  const added: number[] = []
  await page.route('**/api/v1/courses/31/outline-items', async (route) => {
    const body = route.request().postDataJSON() as { contentId: number }
    added.push(body.contentId)
    const item = { id: 300, itemType: 'material', contentId: body.contentId, title: 'a.pdf', position: 1 }
    state.outline.items.push(item)
    await fulfillJson(route, item, 201)
  })
  await page.route('**/api/v1/courses/31/materials/upload-tickets', async (route) => {
    const body = route.request().postDataJSON() as { name: string }
    if (body.name === 'b.pdf') {
      await route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({ type: 'about:blank', title: '服务异常', status: 500, detail: '存储暂不可用' }),
      })
      return
    }
    await fulfillJson(
      route,
      {
        materialId: 91,
        method: 'PUT',
        url: 'https://oss.invalid/up-91',
        requiredHeaders: { 'Content-Type': 'application/pdf' },
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
        'Access-Control-Allow-Headers': 'content-type',
      },
    }),
  )
  await page.route('**/api/v1/courses/31/materials/91/upload-confirmation', (route) =>
    fulfillJson(route, { ok: true }),
  )

  await page.goto('/admin/courses/31?tab=outline')
  await page.locator('.manager-actions').getByRole('button', { name: '添加内容' }).click()
  const picker = page.getByRole('dialog', { name: '添加内容' })
  await picker.getByRole('tab', { name: '本地上传' }).click()
  await picker.locator('input[type="file"]').setInputFiles([
    { name: 'a.pdf', mimeType: 'application/pdf', buffer: Buffer.from('a') },
    { name: 'b.pdf', mimeType: 'application/pdf', buffer: Buffer.from('b') },
  ])
  await picker.getByRole('button', { name: '添加', exact: true }).click()

  await expect(page.getByText('存储暂不可用')).toBeVisible()
  expect(added).toEqual([91])
  await expect(picker).toBeVisible()
  await expect(picker.locator('.local-list')).toContainText('b.pdf')
  await expect(picker.locator('.local-list')).not.toContainText('a.pdf')
})
