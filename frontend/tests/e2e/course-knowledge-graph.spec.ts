import { expect, test } from './fixture'

const GRAPH = {
  id: 8,
  courseId: 3,
  name: '课程知识图谱',
  published: true,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
}

const NODES = [
  {
    id: 1,
    parentId: null,
    position: 1,
    kind: 'unit',
    kpType: null,
    label: '第三章 几何光学',
    summary: null,
    definition: null,
    explanation: null,
    aliases: [],
    code: null,
    language: null,
    sourceSectionTitle: null,
    quote: null,
    resources: [],
  },
  {
    id: 2,
    parentId: 1,
    position: 1,
    kind: 'knowledge_point',
    kpType: '规则',
    label: '光的反射定律',
    summary: null,
    definition: '反射角等于入射角。',
    explanation: null,
    aliases: ['反射律'],
    code: null,
    language: null,
    sourceSectionTitle: '3.1 反射',
    quote: '光在两种介质分界面上……',
    resources: [{ itemType: 'question', contentId: 20, title: '反射练习' }],
  },
]

test.beforeEach(async ({ page }) => {
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
})

test('空知识图谱显示简洁状态，不补写说明或渲染空画布', async ({ page }) => {
  await page.route(/\/api\/v1\/courses\/3\/knowledge-graphs\/8(\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ graph: GRAPH, nodes: [], edges: [] }),
    })
  })

  await page.goto('/courses/3/knowledge-graphs/8')

  await expect(page.getByRole('heading', { name: '课程知识图谱', exact: true })).toBeVisible()
  await expect(page.getByText('暂无知识节点', { exact: true })).toBeVisible()
  await expect(page.locator('.mind-map')).toHaveCount(0)
})

test('?node= 定位选中该节点', async ({ page }) => {
  await page.route(/\/api\/v1\/courses\/3\/knowledge-graphs\/8(\?.*)?$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ graph: GRAPH, nodes: NODES, edges: [] }),
    })
  })

  await page.goto('/courses/3/knowledge-graphs/8?node=2')

  const inspector = page.locator('.graph-detail')
  await expect(inspector.getByRole('heading', { name: '光的反射定律' })).toBeVisible()
  await expect(inspector.getByText('反射角等于入射角。')).toBeVisible()
  await expect(inspector.getByText('3.1 反射')).toBeVisible()
  await expect(inspector.getByText('反射练习')).toBeVisible()
})
