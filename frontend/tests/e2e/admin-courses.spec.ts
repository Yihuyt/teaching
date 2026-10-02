import { expect, test } from './fixture'

test('教师管理列表只请求本人拥有的课程范围', async ({ page }) => {
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
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/courses?**', async (route) => {
    const query = new URL(route.request().url()).searchParams
    expect(query.get('management')).toBe('true')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: 31,
            ownerId: 5,
            title: '教师一负责的课程',
            descriptionMarkdown: '',
            published: false,
            createdAt: '2026-07-23T00:00:00Z',
            updatedAt: '2026-07-23T00:00:00Z',
          },
        ],
        page: 1,
        size: 20,
        total: 1,
        totalPages: 1,
      }),
    })
  })
  await page.goto('/admin/courses')

  await expect(page.getByText('教师一负责的课程', { exact: true })).toBeVisible()
  await expect(page.getByText('作为成员加入的他人课程', { exact: true })).toHaveCount(0)
})

test('课程发布与取消发布走独立命令', async ({ page }) => {
  let published = false
  const course = () => ({
    id: 31,
    ownerId: 5,
    title: '教师一负责的课程',
    descriptionMarkdown: '',
    published,
    createdAt: '2026-07-23T00:00:00Z',
    updatedAt: '2026-07-23T00:00:00Z',
  })

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
        token: 'csrf-token',
        parameterName: '_csrf',
      }),
    })
  })
  await page.route('**/api/v1/courses?**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [course()], page: 1, size: 20, total: 1, totalPages: 1 }),
    })
  })
  const methods: string[] = []
  await page.route('**/api/v1/courses/31/publication', async (route) => {
    methods.push(route.request().method())
    expect(route.request().postData()).toBeNull()
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    published = route.request().method() === 'POST'
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(course()),
    })
  })
  await page.goto('/admin/courses')

  const row = page.getByRole('row').filter({ hasText: '教师一负责的课程' })
  await expect(row.getByText('未发布', { exact: true })).toBeVisible()
  await row.getByRole('button', { name: '发布' }).click()
  await expect(page.getByText('确定发布课程“教师一负责的课程”吗？', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('课程已发布', { exact: true })).toBeVisible()
  await expect(row.getByText('已发布', { exact: true })).toBeVisible()

  await row.getByRole('button', { name: '取消发布' }).click()
  await expect(page.getByText('确定取消发布课程“教师一负责的课程”吗？', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('课程已取消发布', { exact: true })).toBeVisible()
  await expect(row.getByText('未发布', { exact: true })).toBeVisible()
  expect(methods).toEqual(['POST', 'DELETE'])
})

test('教师可以在课程管理中创建课程公告', async ({ page }) => {
  let createdAnnouncement:
    | {
        id: number
        courseId: number
        ownerId: number
        title: string
        contentMarkdown: string
        createdAt: string
        updatedAt: string
      }
    | undefined

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
        items: [
          {
            id: 31,
            ownerId: 5,
            title: '软件工程',
            descriptionMarkdown: '',
            published: true,
            createdAt: '2026-07-23T00:00:00Z',
            updatedAt: '2026-07-23T00:00:00Z',
          },
        ],
        page: 1,
        size: 20,
        total: 1,
        totalPages: 1,
      }),
    })
  })
  await page.route('**/api/v1/courses/31/management', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 31,
        ownerId: 5,
        title: '软件工程',
        descriptionMarkdown: '',
        published: true,
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/courses/31/management/outline', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], units: [] }),
    })
  })
  await page.route('**/api/v1/notifications/announcements?**', async (route) => {
    const query = new URL(route.request().url()).searchParams
    expect(query.get('courseId')).toBe('31')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: createdAnnouncement ? [createdAnnouncement] : [],
        page: 1,
        size: 100,
        total: createdAnnouncement ? 1 : 0,
        totalPages: createdAnnouncement ? 1 : 0,
      }),
    })
  })
  await page.route('**/api/v1/notifications/announcements', async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    expect(route.request().postDataJSON()).toEqual({
      courseId: 31,
      title: '第一次课安排',
      contentMarkdown: '请提前完成环境检查。',
    })
    createdAnnouncement = {
      id: 81,
      courseId: 31,
      ownerId: 5,
      title: '第一次课安排',
      contentMarkdown: '请提前完成环境检查。',
      createdAt: '2026-07-23T00:00:00Z',
      updatedAt: '2026-07-23T00:00:00Z',
    }
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify(createdAnnouncement),
    })
  })

  await page.goto('/admin/courses')
  await page.getByRole('button', { name: '管理课程' }).click()
  await expect(page).toHaveURL(/\/admin\/courses\/31$/)
  await expect(page.getByRole('tab', { name: '资料库' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '课程内容' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '知识图谱' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '成员与课程码' })).toBeVisible()
  await page.getByRole('tab', { name: '公告' }).click()
  await page.getByRole('button', { name: '创建公告' }).click()

  const dialog = page.getByRole('dialog', { name: '创建课程公告' })
  await dialog.getByRole('textbox').nth(0).fill('第一次课安排')
  await dialog.getByRole('textbox').nth(1).fill('请提前完成环境检查。')
  await dialog.getByRole('button', { name: '保存' }).click()

  await expect(page.getByText('课程公告已保存')).toBeVisible()
  await expect(page.getByText('第一次课安排', { exact: true })).toBeVisible()
})

test('知识图谱只在具体课程中创建和管理', async ({ page }) => {
  let graphs: Array<{
    id: number
    courseId: number
    name: string
    published: boolean
    createdAt: string
    updatedAt: string
  }> = []

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
        token: 'csrf-token',
        parameterName: '_csrf',
      }),
    })
  })
  await page.route('**/api/v1/courses/31/management', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 31,
        ownerId: 5,
        title: '软件工程',
        descriptionMarkdown: '',
        published: false,
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/courses/31/management/outline', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], units: [] }),
    })
  })
  await page.route('**/api/v1/courses/31/knowledge-graph-builds', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })
  await page.route(/\/api\/v1\/courses\/31\/knowledge-graphs(\?.*)?$/, async (route) => {
    if (route.request().method() === 'POST') {
      expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
      expect(route.request().postDataJSON()).toEqual({ name: '软件工程知识图谱' })
      graphs = [
        {
          id: 91,
          courseId: 31,
          name: '软件工程知识图谱',
          published: false,
          createdAt: '2026-07-23T00:00:00Z',
          updatedAt: '2026-07-23T00:00:00Z',
        },
      ]
      await route.fulfill({
        status: 201,
        contentType: 'application/json',
        body: JSON.stringify(graphs[0]),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(graphs),
    })
  })

  await page.route('**/api/v1/courses/31/knowledge-graphs/91/publication', async (route) => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['x-csrf-token']).toBe('csrf-token')
    graphs = graphs.map((g) => ({ ...g, published: true }))
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(graphs[0]) })
  })

  await page.goto('/admin/courses/31')
  await page.getByRole('tab', { name: '知识图谱' }).click()
  await page.getByRole('button', { name: '创建图谱' }).click()

  const dialog = page.getByRole('dialog', { name: '创建知识图谱' })
  await dialog.getByLabel('图谱名称').fill('软件工程知识图谱')
  await dialog.getByRole('button', { name: '保存' }).click()

  await expect(page.getByText('知识图谱已保存')).toBeVisible()
  await expect(page.getByText('软件工程知识图谱', { exact: true })).toBeVisible()
  const graphsPanel = page.getByLabel('知识图谱', { exact: true })
  await expect(graphsPanel.getByText('未发布', { exact: true })).toBeVisible()

  await graphsPanel.getByRole('button', { name: '发布', exact: true }).click()
  await expect(page.getByText('知识图谱已发布', { exact: true })).toBeVisible()
  await expect(graphsPanel.getByText('已发布', { exact: true })).toBeVisible()
})

test('抽取失败的构建:向导停在抽取面板,可忽略失败小节直接合并', async ({ page }) => {
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
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/session/csrf', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token', parameterName: '_csrf' }),
    })
  })
  await page.route('**/api/v1/courses/31/management', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 31,
        ownerId: 5,
        title: '软件工程',
        descriptionMarkdown: '',
        published: false,
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/courses/31/management/outline', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], units: [] }),
    })
  })
  await page.route(/\/api\/v1\/courses\/31\/knowledge-graphs(\?.*)?$/, async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })

  const failedBuild = {
    id: 71,
    courseId: 31,
    materialId: 9,
    materialName: '教材.pdf',
    status: 'failed',
    errorMessage: '1 个小节抽取失败，可重试（只跑未完成的小节），或忽略失败小节直接合并：失败节 — 类型不合法',
    createdAt: '2026-07-23T00:00:00Z',
    updatedAt: '2026-07-23T00:00:00Z',
  }
  await page.route('**/api/v1/courses/31/knowledge-graph-builds', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([failedBuild]) })
  })
  const tocEntry = { number: '1', title: '第一章', level: 1, page: 1, endPage: 10 }
  await page.route('**/api/v1/courses/31/knowledge-graph-builds/71', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        build: failedBuild,
        degraded: false,
        notes: [],
        pageCount: 10,
        tocDraft: [tocEntry],
        tocConfirmed: [tocEntry],
        sections: [
          {
            sectionIndex: 0,
            title: '成功节',
            path: '第一章',
            startPage: 1,
            endPage: 1,
            status: 'done',
            errorMessage: null,
          },
          {
            sectionIndex: 1,
            title: '失败节',
            path: '第一章',
            startPage: 2,
            endPage: 2,
            status: 'failed',
            errorMessage: '类型不合法',
          },
        ],
      }),
    })
  })
  let mergeCalled = 0
  await page.route('**/api/v1/courses/31/knowledge-graph-builds/71/merge', async (route) => {
    mergeCalled += 1
    expect(route.request().method()).toBe('POST')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...failedBuild, status: 'extracting', errorMessage: null }),
    })
  })
  await page.route('**/api/v1/courses/31/knowledge-graph-builds/71/events', async (route) => {
    await route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' })
  })

  await page.goto('/admin/courses/31')
  await page.getByRole('tab', { name: '知识图谱' }).click()
  await page.getByRole('button', { name: '教材.pdf(失败)' }).click()

  const dialog = page.getByRole('dialog', { name: '从教材生成知识图谱' })
  await expect(dialog.getByText('或忽略失败小节直接合并')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '重试抽取（只跑未完成的小节）' })).toBeVisible()
  await dialog.getByRole('button', { name: '忽略失败小节，直接合并' }).click()
  await expect.poll(() => mergeCalled).toBe(1)
})

test('资料库只从当前课程接口加载且不显示公开属性', async ({ page }) => {
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
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/courses/31/management', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 31,
        ownerId: 5,
        title: '软件工程',
        descriptionMarkdown: '',
        published: true,
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      }),
    })
  })
  await page.route('**/api/v1/courses/31/management/outline', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], units: [] }),
    })
  })
  await page.route('**/api/v1/courses/31/materials', async (route) => {
    expect(route.request().method()).toBe('GET')
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        {
          id: 81,
          courseId: 31,
          parentId: null,
          name: '第一章讲义.pdf',
          kind: 'file',
          contentType: 'application/pdf',
          sizeBytes: 2048,
          sha256: 'a'.repeat(64),
          state: 'active',
          createdAt: '2026-07-23T00:00:00Z',
          updatedAt: '2026-07-23T00:00:00Z',
        },
      ]),
    })
  })

  await page.route('**/api/v1/courses/31/questions', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })
  await page.route('**/api/v1/courses/31/programming-problems', async (route) => {
    await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
  })

  await page.goto('/admin/courses/31')
  await page.getByRole('tab', { name: '资料库' }).click()

  await expect(page.getByText('第一章讲义.pdf', { exact: true })).toBeVisible()
  await expect(page.getByText('文件', { exact: true })).toBeVisible()
  await expect(page.getByText('公开', { exact: true })).toHaveCount(0)
  await expect(page.getByText('仅自己', { exact: true })).toHaveCount(0)
})
