import MockAdapter from 'axios-mock-adapter'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import { http } from '@/api/client'
import { installSessionExpiryHandling } from '@/features/auth/sessionExpiry'
import { AccountViewRole } from '@/api/generated'
import { useSessionStore } from '@/stores/session'

describe('会话过期处理', () => {
  it('清空已登录账户并导航到登录页', async () => {
    const pinia = createPinia()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', name: 'login', component: { template: '<div />' } },
        { path: '/courses', name: 'courses', component: { template: '<div />' } },
      ],
    })
    await router.push('/courses')
    await router.isReady()

    const session = useSessionStore(pinia)
    session.$patch({
      initialized: true,
      account: {
        id: 9,
        username: 'student01',
        role: AccountViewRole.student,
        credentialState: 'active',
        displayName: '学生一',
        enabled: true,
        mustResetPassword: false,
        createdAt: '2026-07-23T00:00:00Z',
        updatedAt: '2026-07-23T00:00:00Z',
      },
    })

    const uninstall = installSessionExpiryHandling(pinia, router)
    const mock = new MockAdapter(http)
    mock.onGet('/courses').reply(401, {
      type: 'https://teaching.example/problems/unauthorized',
      title: '未登录',
      status: 401,
      detail: '会话已过期',
    })

    await expect(http.get('/courses')).rejects.toMatchObject({ status: 401 })
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(router.currentRoute.value.query.redirect).toBe('/courses')
    expect(session.account).toBeNull()
    expect(session.initialized).toBe(true)

    uninstall()
    mock.restore()
  })
})
