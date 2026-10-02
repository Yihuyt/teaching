import MockAdapter from 'axios-mock-adapter'
import { createPinia, setActivePinia } from 'pinia'

import { http } from '@/api/client'
import router from '@/app/router'
import { usePlatformStore } from '@/stores/platform'
import { useSessionStore } from '@/stores/session'

describe('路由会话门禁', () => {
  const mock = new MockAdapter(http)

  beforeEach(async () => {
    setActivePinia(createPinia())
    usePlatformStore().replace({
      siteName: '智能教学平台',
      footerText: '',
      updatedAt: '2026-07-23T00:00:00Z',
    })
    mock.reset()
    await router.replace('/service-unavailable')
  })

  afterAll(() => {
    mock.restore()
  })

  it('会话服务错误时进入服务不可用页，而不是登录页', async () => {
    mock.onGet('/api/v1/session').reply(503, {
      type: 'https://teaching.example/problems/unavailable',
      title: '服务不可用',
      status: 503,
      detail: '会话服务正在维护',
    })

    await router.push('/courses')

    expect(router.currentRoute.value.name).toBe('service-unavailable')
    expect(useSessionStore().initialized).toBe(false)
  })

  it('匿名访问未知地址时先进入登录页', async () => {
    mock.onGet('/api/v1/session').reply(401, {
      type: 'https://teaching.example/problems/unauthorized',
      title: '未登录',
      status: 401,
      detail: '当前会话未登录',
    })

    await router.push('/不存在的页面')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/不存在的页面')
  })

  it('已登录账户访问未知地址时显示页面不存在', async () => {
    mock.onGet('/api/v1/session').reply(200, {
      id: 9,
      username: 'student01',
      role: 'student',
      credentialState: 'active',
      displayName: '学生一',
      enabled: true,
      mustResetPassword: false,
      createdAt: '2026-07-23T00:00:00Z',
      updatedAt: '2026-07-23T00:00:00Z',
    })

    await router.push('/不存在的页面')

    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('已删除的普通资源地址不保留跳转或隐藏页面', async () => {
    mock.onGet('/api/v1/session').reply(200, {
      id: 9,
      username: 'student01',
      role: 'student',
      credentialState: 'active',
      displayName: '学生一',
      enabled: true,
      mustResetPassword: false,
      createdAt: '2026-07-23T00:00:00Z',
      updatedAt: '2026-07-23T00:00:00Z',
    })

    await router.push('/resources')

    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('知识图谱不保留平台级后台入口', async () => {
    mock.onGet('/api/v1/session').reply(200, {
      id: 5,
      username: 'teacher01',
      role: 'teacher',
      credentialState: 'active',
      displayName: '教师一',
      enabled: true,
      mustResetPassword: false,
      createdAt: '2026-07-23T00:00:00Z',
      updatedAt: '2026-07-23T00:00:00Z',
    })

    await router.push('/admin/knowledge-graphs')

    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it.each(['root', 'admin', 'teacher', 'student'] as const)(
    '%s 角色进入普通界面的课程试题时使用同一学习路由',
    async (role) => {
      mock.onGet('/api/v1/session').reply(200, {
        id: 9,
        username: `${role}01`,
        role,
        credentialState: 'active',
        displayName: '学习用户',
        enabled: true,
        mustResetPassword: false,
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      })

      await router.push('/courses/3/questions/8')

      expect(router.currentRoute.value.name).toBe('course-question')
    },
  )
})
