import MockAdapter from 'axios-mock-adapter'
import { createPinia, setActivePinia } from 'pinia'

import { http } from '@/api/client'
import { AccountViewRole } from '@/api/generated'
import { useSessionStore } from '@/stores/session'

describe('会话初始化', () => {
  const mock = new MockAdapter(http)

  beforeEach(() => {
    setActivePinia(createPinia())
    mock.reset()
  })

  afterAll(() => {
    mock.restore()
  })

  it('当前账户加载成功后完成初始化', async () => {
    mock.onGet('/api/v1/session').reply(200, {
      id: 9,
      username: 'student01',
      role: AccountViewRole.student,
      credentialState: 'active',
      displayName: '学生一',
      enabled: true,
      mustResetPassword: false,
      createdAt: '2026-07-23T00:00:00Z',
      updatedAt: '2026-07-23T00:00:00Z',
    })
    const session = useSessionStore()

    await session.initialize()

    expect(session.initialized).toBe(true)
    expect(session.account?.username).toBe('student01')
  })

  it('明确未登录时完成初始化并保持匿名状态', async () => {
    mock.onGet('/api/v1/session').reply(401, {
      type: 'https://teaching.example/problems/unauthorized',
      title: '未登录',
      status: 401,
      detail: '当前会话未登录',
    })
    const session = useSessionStore()

    await session.initialize()

    expect(session.initialized).toBe(true)
    expect(session.account).toBeNull()
  })

  it('网络失败时保持未初始化并允许重试', async () => {
    mock.onGet('/api/v1/session').networkErrorOnce()
    mock.onGet('/api/v1/session').reply(401, {
      type: 'https://teaching.example/problems/unauthorized',
      title: '未登录',
      status: 401,
      detail: '当前会话未登录',
    })
    const session = useSessionStore()

    await expect(session.initialize()).rejects.toThrow('无法连接教学平台服务')
    expect(session.initialized).toBe(false)

    await session.initialize()
    expect(session.initialized).toBe(true)
    expect(mock.history.get).toHaveLength(2)
  })

  it('服务端错误时不伪装成未登录', async () => {
    mock.onGet('/api/v1/session').reply(500, {
      type: 'https://teaching.example/problems/internal',
      title: '服务端错误',
      status: 500,
      detail: '会话服务暂不可用',
    })
    const session = useSessionStore()

    await expect(session.initialize()).rejects.toMatchObject({
      status: 500,
      message: '会话服务暂不可用',
    })
    expect(session.initialized).toBe(false)
    expect(session.account).toBeNull()
  })
})
