import { expect, test, type Page } from './fixture'

interface StubNode {
  id: number
  parentId: number | null
  position: number
  kind: string
  kpType: string | null
  label: string
  summary: string | null
  definition: string | null
  explanation: string | null
  aliases: string[]
  code: string | null
  language: string | null
  sourceSectionTitle: string | null
  quote: string | null
  resources: unknown[]
}

function makeState() {
  return {
    graph: {
      id: 91,
      courseId: 31,
      name: '软件工程知识图谱',
      published: true,
      createdAt: '2026-07-23T00:00:00Z',
      updatedAt: '2026-07-23T00:00:00Z',
    },
    nodes: [] as StubNode[],
    edges: [] as Array<{ id: number; sourceNodeId: number; targetNodeId: number; kind: string; evidence: string | null }>,
    snapshotRequests: 0,
  }
}

type State = ReturnType<typeof makeState>

async function stubEditor(page: Page, state: State): Promise<void> {
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
      body: JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' }),
    })
  })
  await page.route(/\/api\/v1\/courses\/31\/knowledge-graphs\/91(\?.*)?$/, async (route) => {
    state.snapshotRequests += 1
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ graph: state.graph, nodes: state.nodes, edges: state.edges }),
    })
  })
}

const kp = (id: number, position: number, label: string): StubNode => ({
  id,
  parentId: null,
  position,
  kind: 'knowledge_point',
  kpType: '概念',
  label,
  summary: null,
  definition: null,
  explanation: null,
  aliases: [],
  code: null,
  language: null,
  sourceSectionTitle: null,
  quote: null,
  resources: [],
})

function snapshotBody(state: State): string {
  return JSON.stringify({ graph: state.graph, nodes: state.nodes, edges: state.edges })
}

test('空图谱从根动作即时建章节与知识点，无保存按钮', async ({ page }) => {
  const state = makeState()
  await stubEditor(page, state)
  let nextId = 1
  await page.route('**/api/v1/courses/31/knowledge-graphs/91/nodes', async (route) => {
    expect(route.request().method()).toBe('POST')
    const payload = route.request().postDataJSON() as Record<string, unknown>
    const id = nextId++
    state.nodes.push({
      id,
      parentId: (payload.parentId as number | null) ?? null,
      position: state.nodes.filter((node) => node.parentId === (payload.parentId ?? null)).length + 1,
      kind: payload.kind as string,
      kpType: (payload.kpType as string | null) ?? null,
      label: payload.label as string,
      summary: (payload.summary as string | null) ?? null,
      definition: (payload.definition as string | null) ?? null,
      explanation: (payload.explanation as string | null) ?? null,
      aliases: (payload.aliases as string[] | null) ?? [],
      code: null,
      language: null,
      sourceSectionTitle: null,
      quote: null,
      resources: [],
    })
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ createdNodeId: id, snapshot: JSON.parse(snapshotBody(state)) }),
    })
  })

  await page.goto('/focus/admin/courses/31/knowledge-graphs/91/edit')

  await expect(page.getByText('还没有内容')).toBeVisible()
  await expect(page.getByRole('button', { name: '保存' })).toHaveCount(0)

  await page.locator('.graph-empty').click({ button: 'right' })
  const contextMenu = page.locator('.context-menu')
  await expect(contextMenu.locator('li')).toHaveText(['添加章节', '添加知识点'])
  await contextMenu.getByText('添加章节').click()
  const unitDialog = page.getByRole('dialog', { name: '添加章节' })
  await unitDialog.getByLabel('名称').fill('第一章 引言')
  await unitDialog.getByRole('button', { name: '保存' }).click()

  const inspector = page.locator('.inspector-host')
  await expect(inspector.getByRole('heading', { name: '第一章 引言' })).toBeVisible()

  await inspector.getByRole('button', { name: '知识点' }).click()
  const kpDialog = page.getByRole('dialog', { name: '添加知识点' })
  await kpDialog.getByLabel('名称').fill('软件危机')
  await kpDialog.getByRole('button', { name: '保存' }).click()

  await expect(inspector.getByRole('heading', { name: '软件危机' })).toBeVisible()
  expect(state.nodes.map((node) => [node.kind, node.label, node.parentId])).toEqual([
    ['unit', '第一章 引言', null],
    ['knowledge_point', '软件危机', 1],
  ])
})

test('删除带子节点的章节提示「该条目和其他部分关联」，确认后级联删除', async ({ page }) => {
  const state = makeState()
  state.nodes = [
    {
      id: 1,
      parentId: null,
      position: 1,
      kind: 'unit',
      kpType: null,
      label: '第一章',
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
      kpType: '概念',
      label: '软件危机',
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
  ]
  await stubEditor(page, state)
  await page.route('**/api/v1/courses/31/knowledge-graphs/91/nodes/1', async (route) => {
    expect(route.request().method()).toBe('DELETE')
    state.nodes = []
    await route.fulfill({ status: 200, contentType: 'application/json', body: snapshotBody(state) })
  })

  await page.goto('/focus/admin/courses/31/knowledge-graphs/91/edit?node=1')
  const inspector = page.locator('.inspector-host')
  await expect(inspector.getByRole('heading', { name: '第一章' })).toBeVisible()

  await inspector.getByRole('button', { name: '删除' }).click()
  await expect(page.getByText('该条目和其他部分关联，确认删除？')).toBeVisible()
  await page.getByRole('button', { name: '确定' }).click()

  await expect(page.getByText('还没有内容')).toBeVisible()
})

test('前置成环被拒并展示环路文案；节点已被删除时提示并重载快照', async ({ page }) => {
  const state = makeState()
  state.nodes = [kp(1, 1, '甲'), kp(2, 2, '乙'), kp(3, 3, '丙')]
  state.edges = [
    { id: 1, sourceNodeId: 1, targetNodeId: 2, kind: 'prerequisite', evidence: null },
    { id: 2, sourceNodeId: 2, targetNodeId: 3, kind: 'prerequisite', evidence: null },
  ]
  await stubEditor(page, state)
  await page.route('**/api/v1/courses/31/knowledge-graphs/91/edges', async (route) => {
    expect(route.request().postDataJSON()).toEqual({ sourceNodeId: 3, targetNodeId: 1, kind: 'prerequisite' })
    await route.fulfill({
      status: 409,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        type: 'urn:teaching:problem:domain-error',
        title: '状态冲突',
        status: 409,
        detail: '会形成循环前置：丙 → 甲 → 乙 → 丙',
      }),
    })
  })
  await page.route('**/api/v1/courses/31/knowledge-graphs/91/nodes/1', async (route) => {
    await route.fulfill({
      status: 404,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        type: 'urn:teaching:problem:domain-error',
        title: '资源不存在',
        status: 404,
        detail: '节点已被删除，请刷新',
      }),
    })
  })

  await page.goto('/focus/admin/courses/31/knowledge-graphs/91/edit?node=1')
  const inspector = page.locator('.inspector-host')
  await expect(inspector.getByRole('heading', { name: '甲' })).toBeVisible()

  // 甲已与乙相连,候选只剩丙;丙 → 甲会经 甲 → 乙 → 丙 成环
  await inspector.getByRole('button', { name: '添加前置' }).click()
  const edgeDialog = page.getByRole('dialog', { name: '添加前置知识点' })
  await edgeDialog.getByRole('combobox').click()
  await page.getByRole('option', { name: '丙' }).click()
  await edgeDialog.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('会形成循环前置：丙 → 甲 → 乙 → 丙')).toBeVisible()

  const before = state.snapshotRequests
  await edgeDialog.getByRole('button', { name: '取消' }).click()
  await inspector.getByRole('button', { name: '编辑' }).click()
  const editDialog = page.getByRole('dialog', { name: '编辑知识点' })
  await editDialog.getByLabel('名称').fill('甲二')
  await editDialog.getByRole('button', { name: '保存' }).click()

  await expect(page.getByText('节点已被删除，请刷新')).toBeVisible()
  await expect.poll(() => state.snapshotRequests).toBeGreaterThan(before)
})

test('挂载对话框支持上传本地文件，入库后直接挂载到当前节点', async ({ page }) => {
  const state = makeState()
  state.nodes.push(kp(1, 1, '循环结构'))
  await stubEditor(page, state)

  const materials: unknown[] = []
  const uploadedFile = {
    id: 90,
    courseId: 31,
    parentId: null,
    name: '章节讲义.pdf',
    kind: 'file',
    contentType: 'application/pdf',
    sizeBytes: 4096,
    sha256: 'a'.repeat(64),
    state: 'active',
    createdAt: '2026-07-23T00:00:00Z',
    updatedAt: '2026-07-23T00:00:00Z',
  }
  await page.route(/\/api\/v1\/courses\/31\/materials(\?.*)?$/, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(materials) }),
  )
  await page.route('**/api/v1/courses/31/questions', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/v1/courses/31/programming-problems', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/v1/courses/31/materials/upload-tickets', async (route) => {
    expect(route.request().postDataJSON()).toMatchObject({ parentId: null, name: '章节讲义.pdf' })
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        materialId: 90,
        method: 'PUT',
        url: 'https://oss.invalid/upload-90',
        requiredHeaders: {
          'Content-Type': 'application/pdf',
          'x-oss-meta-sha256': 'stub',
          'x-oss-forbid-overwrite': 'true',
        },
      }),
    })
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
  await page.route('**/api/v1/courses/31/materials/90/upload-confirmation', async (route) => {
    materials.push(uploadedFile)
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(uploadedFile) })
  })
  let attachBody: unknown = null
  await page.route('**/api/v1/courses/31/knowledge-graphs/91/nodes/1/resources', async (route) => {
    attachBody = route.request().postDataJSON()
    state.nodes[0]!.resources = [{ itemType: 'material', contentId: 90, title: '章节讲义.pdf' }]
    await route.fulfill({ status: 200, contentType: 'application/json', body: snapshotBody(state) })
  })

  await page.goto('/focus/admin/courses/31/knowledge-graphs/91/edit?node=1')
  await page.getByRole('button', { name: '挂载', exact: true }).click()

  const dialog = page.getByRole('dialog', { name: '挂载资源' })
  await expect(dialog).toBeVisible()
  await dialog.locator('input[type="file"]').setInputFiles({
    name: '章节讲义.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('pdf-bytes'),
  })

  await expect(page.getByText('“章节讲义.pdf”已上传')).toBeVisible()
  await expect.poll(() => attachBody).toEqual({ itemType: 'material', contentId: 90 })
  await dialog.getByRole('button', { name: '关闭' }).last().click()
  await expect(page.getByRole('button', { name: '章节讲义.pdf', exact: true })).toBeVisible()
})
