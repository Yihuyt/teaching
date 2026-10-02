import { expect, test, type Page } from './fixture'

interface StageStub {
  title: string
  theme: string
  scenes: Array<Record<string, unknown>>
}

const page1 = {
  id: 'scene-1',
  type: 'content',
  title: '光的反射',
  preset: 'standard',
  summary: '入射角与反射角',
  blocks: [{ id: 'blk-paragraph-1', type: 'paragraph', text: '反射角等于入射角。' }],
  speech: [],
  interactive: null,
}
const page2 = {
  id: 'scene-2',
  type: 'content',
  title: '平面镜成像',
  preset: 'standard',
  summary: '像与物',
  blocks: [{ id: 'blk-paragraph-1', type: 'paragraph', text: '像与物等大。' }],
  speech: [],
  interactive: null,
}

function makeState() {
  return {
    stage: { title: '光学第一课', theme: 'default', scenes: [page1, page2] } as StageStub,
    published: false,
    detailRequests: 0,
    opsBodies: [] as Array<Record<string, unknown>>,
    outlineRequests: [] as Array<Record<string, unknown>>,
    generateRequests: [] as Array<Record<string, unknown>>,
  }
}

type State = ReturnType<typeof makeState>

function detailBody(state: State): string {
  return JSON.stringify({
    id: 10,
    published: state.published,
    stage: state.stage,
    assetUrls: {},
  })
}

function sse(events: Array<Record<string, unknown>>): string {
  return events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join('')
}

async function stubWorkbench(page: Page, state: State): Promise<void> {
  await page.route('**/api/v1/session', (route) =>
    route.fulfill({
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
    }),
  )
  await page.route('**/api/v1/session/csrf', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-token' }),
    }),
  )
  await page.route(/\/api\/v1\/courses\/6\/coursewares\/10$/, (route) => {
    state.detailRequests += 1
    return route.fulfill({ status: 200, contentType: 'application/json', body: detailBody(state) })
  })
  await page.route('**/api/v1/courses/6/coursewares/10/materials', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/v1/courses/6/coursewares/10/ops', async (route) => {
    const body = route.request().postDataJSON() as { ops: Array<Record<string, unknown>> }
    state.opsBodies.push(...body.ops)
    for (const op of body.ops) {
      if (op.op === 'move_scene') {
        const scenes = state.stage.scenes
        const index = scenes.findIndex((p) => p.id === op.sceneId)
        const [moved] = scenes.splice(index, 1)
        scenes.splice(op.toIndex as number, 0, moved!)
      }
      if (op.op === 'update_stage_meta' && typeof op.title === 'string') state.stage.title = op.title
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: detailBody(state) })
  })
  await page.route('**/api/v1/courses/6/coursewares/10/outline', async (route) => {
    state.outlineRequests.push(route.request().postDataJSON() as Record<string, unknown>)
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sse([
        {
          type: 'outline',
          title: '光学第一课',
          scenes: [
            { title: '封面', type: 'content', preset: 'title-cover', summary: '课程封面' },
            { title: '光的折射', type: 'content', preset: 'standard', summary: '折射定律' },
            { title: '随堂测验', type: 'quiz', preset: 'quiz', summary: '折射定律小测' },
          ],
          images: [],
        },
      ]),
    })
  })
  await page.route('**/api/v1/courses/6/coursewares/10/generate', async (route) => {
    const body = route.request().postDataJSON() as Record<string, unknown>
    state.generateRequests.push(body)
    const scenes = body.scenes as Array<Record<string, unknown>>
    state.stage.scenes = scenes.map((scene, i) => ({
      ...page1,
      id: `scene-${i + 1}`,
      type: scene.type,
      title: scene.title,
      preset: scene.preset,
      summary: scene.summary,
      blocks:
        scene.type === 'quiz'
          ? [
              {
                id: 'blk-quiz_choice-1',
                type: 'quiz_choice',
                stem: '题干',
                options: [
                  { label: 'A', text: '甲' },
                  { label: 'B', text: '乙' },
                ],
                answer: ['A'],
                multiple: false,
                explanation: '解析',
              },
            ]
          : page1.blocks,
    }))
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sse([
        { type: 'scene_start', order: 1, total: 3, title: '封面' },
        { type: 'scene_done', order: 1, sceneId: 'scene-1', version: 2, warnings: [] },
        { type: 'scene_start', order: 2, total: 3, title: '光的折射' },
        { type: 'scene_done', order: 2, sceneId: 'scene-2', version: 3, warnings: ['内容偏多,已缩小字号排下'] },
        { type: 'scene_start', order: 3, total: 3, title: '随堂测验' },
        { type: 'scene_failed', order: 3, title: '随堂测验', sceneId: 'scene-3', message: '模型三轮未通过' },
        { type: 'done', version: 4, total: 3, failed: 1 },
      ]),
    })
  })
  await page.route('**/api/v1/courses/6/coursewares/10/publication', async (route) => {
    state.published = route.request().method() !== 'DELETE'
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ published: state.published }),
    })
  })
}

const WORKBENCH = '/focus/admin/courses/6/coursewares/10/edit'

test('载入课件并手工调页序:逐操作即时落库,界面按服务端回包对齐', async ({ page }) => {
  const state = makeState()
  await stubWorkbench(page, state)
  await page.goto(WORKBENCH)

  await expect(page.locator('.stage-title')).toContainText('光学第一课')
  await expect(page.locator('.scene-item')).toHaveCount(2)
  await expect(page.locator('.scene-item').first()).toContainText('光的反射')
  await expect(page.getByRole('tab', { name: '页面' })).toHaveCount(0)

  await page.locator('.scene-item.active').getByRole('button', { name: '下移' }).click()

  await expect(page.locator('.scene-item').first()).toContainText('平面镜成像')
  expect(state.opsBodies).toEqual([{ op: 'move_scene', sceneId: 'scene-1', toIndex: 1 }])
})

test('生成流水线:需求出大纲,教师改大纲后按大纲生成,逐页落库触发重取,失败页有提示', async ({ page }) => {
  const state = makeState()
  await stubWorkbench(page, state)
  await page.goto(WORKBENCH)
  await expect(page.locator('.stage-title')).toContainText('光学第一课')

  await page.getByRole('button', { name: '生成课件' }).click()
  await page.getByPlaceholder(/这门课讲什么/).fill('讲光的折射,面向初二学生')
  await page.getByRole('button', { name: '生成大纲' }).click()

  const outlineItems = page.locator('.outline-item')
  await expect(outlineItems).toHaveCount(3)
  expect(state.outlineRequests[0]).toMatchObject({
    requirement: '讲光的折射,面向初二学生',
    sceneCount: null,
    quizCount: null,
    interactiveCount: null,
  })

  // 改第二页标题后确认生成:现有 2 页会被替换,先确认
  await outlineItems.nth(1).getByPlaceholder('页面标题').fill('折射定律')
  await page.getByRole('button', { name: '按此大纲生成' }).click()
  await page.getByRole('button', { name: '确定' }).click()

  await expect(page.locator('.progress-item.done')).toHaveCount(2)
  await expect(page.locator('.progress-item.failed')).toContainText('模型三轮未通过')
  await expect(page.locator('.el-alert--success')).toContainText('1 页失败')
  expect(state.generateRequests[0]).toMatchObject({ title: '光学第一课' })
  const sent = (state.generateRequests[0]!.scenes as Array<Record<string, unknown>>)[1]
  expect(sent).toMatchObject({ title: '折射定律', type: 'content', widgetType: null, widgetOutline: null, images: [] })

  // 每页落库都触发重取:页面列表按服务端最新课件对齐
  await expect(page.locator('.scene-item')).toHaveCount(3)
  await expect(page.locator('.scene-item').nth(1)).toContainText('折射定律')
  expect(state.detailRequests).toBeGreaterThanOrEqual(2)
})

test('发布是独立布尔开关:发布后标签与按钮翻转,取消发布还原', async ({ page }) => {
  const state = makeState()
  await stubWorkbench(page, state)
  await page.goto(WORKBENCH)

  await expect(page.locator('.stage-title .el-tag')).toContainText('未发布')
  await page.getByRole('button', { name: '发布', exact: true }).click()
  await expect(page.locator('.stage-title .el-tag')).toContainText('已发布')
  await page.getByRole('button', { name: '取消发布' }).click()
  await expect(page.locator('.stage-title .el-tag')).toContainText('未发布')
})
