import { createPinia, setActivePinia } from 'pinia'
import MockAdapter from 'axios-mock-adapter'

import { http } from '@/api/client'
import { usePlatformStore } from '@/stores/platform'

describe('平台公开设置', () => {
  const mock = new MockAdapter(http)

  beforeEach(() => {
    setActivePinia(createPinia())
    mock.reset()
  })

  afterAll(() => {
    mock.restore()
  })

  it('只加载一次并向所有布局提供统一的名称与页脚', async () => {
    mock.onGet('/api/v1/platform/public').reply(200, {
      siteName: '课程教学中心',
      footerText: '统一教学平台',
      updatedAt: '2026-07-23T00:00:00Z',
    })
    const platform = usePlatformStore()

    await platform.initialize()
    await platform.initialize()

    expect(platform.siteName).toBe('课程教学中心')
    expect(platform.footerText).toBe('统一教学平台')
    expect(mock.history.get).toHaveLength(1)
  })

  it('加载失败时不伪造默认设置，允许明确重试', async () => {
    mock.onGet('/api/v1/platform/public').networkErrorOnce()
    mock.onGet('/api/v1/platform/public').reply(200, {
      siteName: '重试后的平台',
      footerText: '',
      updatedAt: '2026-07-23T00:00:00Z',
    })
    const platform = usePlatformStore()

    await expect(platform.initialize()).rejects.toThrow('无法连接教学平台服务')
    expect(platform.initialized).toBe(false)
    expect(platform.settings).toBeNull()

    await platform.initialize()
    expect(platform.siteName).toBe('重试后的平台')
  })
})
