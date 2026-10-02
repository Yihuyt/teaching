import { AxiosHeaders } from 'axios'
import MockAdapter from 'axios-mock-adapter'

import { ApiProblem, clearCsrfToken, errorMessage, http, setUnauthorizedHandler } from '@/api/client'

describe('API 客户端', () => {
  const mock = new MockAdapter(http)

  beforeEach(() => {
    clearCsrfToken()
    setUnauthorizedHandler(undefined)
    mock.reset()
  })

  afterAll(() => {
    mock.restore()
  })

  it('修改请求先获取 CSRF 令牌，并且只保存在内存中', async () => {
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem')
    mock.onGet('/api/v1/session/csrf').reply(200, {
      headerName: 'X-CSRF-TOKEN',
      token: 'csrf-test-token',
    })
    mock.onPost('/probe').reply((config) => {
      const headers = AxiosHeaders.from(config.headers as AxiosHeaders)
      return [200, { csrf: headers.get('X-CSRF-TOKEN') }]
    })

    const first = await http.post<{ csrf: string }>('/probe')
    const second = await http.post<{ csrf: string }>('/probe')

    expect(first.data.csrf).toBe('csrf-test-token')
    expect(second.data.csrf).toBe('csrf-test-token')
    expect(mock.history.get).toHaveLength(1)
    expect(storageSpy).not.toHaveBeenCalled()
  })

  it('拒绝无效的 CSRF 响应，不发送修改请求', async () => {
    mock.onGet('/api/v1/session/csrf').reply(200, { token: '' })
    mock.onDelete('/probe').reply(204)

    await expect(http.delete('/probe')).rejects.toThrow('服务端返回了无效的 CSRF 令牌')
    expect(mock.history.delete).toHaveLength(0)
  })

  it('将 RFC 9457 问题详情转换为明确的业务异常', async () => {
    mock.onGet('/problem').reply(422, {
      type: 'https://teaching.example/problems/validation',
      title: '请求校验失败',
      status: 422,
      detail: '课程名称不能为空',
      instance: '/api/v1/courses',
    })

    const request = http.get('/problem')

    await expect(request).rejects.toBeInstanceOf(ApiProblem)
    await expect(request).rejects.toMatchObject({
      status: 422,
      message: '课程名称不能为空',
    })
  })

  it('错误提示附带服务端分配的请求编号,便于在日志里定位', async () => {
    mock.onGet('/problem').reply(500, {
      type: 'urn:teaching:problem:internal-error',
      title: '服务器内部错误',
      status: 500,
      detail: '服务器处理请求时发生错误',
      requestId: 'a3f9c1d2',
    })

    const error = await http.get('/problem').catch((caught: unknown) => caught)

    expect(errorMessage(error)).toBe('服务器处理请求时发生错误（错误编号 a3f9c1d2）')
  })

  it('网络不可达时明确报告连接失败', async () => {
    mock.onGet('/offline').networkError()

    await expect(http.get('/offline')).rejects.toThrow('无法连接教学平台服务，请确认服务已经启动')
  })

  it('拒绝不符合 RFC 9457 的服务端错误响应', async () => {
    mock.onGet('/invalid-error-contract').reply(500, { message: '内部错误' })

    await expect(http.get('/invalid-error-contract')).rejects.toThrow('服务返回的数据格式错误，请联系管理员')
  })

  it('业务接口返回 401 时发出会话失效通知', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    mock.onGet('/courses').reply(401, {
      type: 'https://teaching.example/problems/unauthorized',
      title: '未登录',
      status: 401,
      detail: '会话已过期',
    })

    await expect(http.get('/courses')).rejects.toMatchObject({ status: 401 })
    expect(handler).toHaveBeenCalledOnce()
  })

  it('登录失败不触发全局会话失效通知', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    mock.onGet('/api/v1/session/csrf').reply(200, {
      headerName: 'X-CSRF-TOKEN',
      token: 'csrf-login-token',
    })
    mock.onPost('/api/v1/session/login').reply(401, {
      type: 'https://teaching.example/problems/unauthorized',
      title: '登录失败',
      status: 401,
      detail: '用户名或密码错误',
    })

    await expect(http.post('/api/v1/session/login', {})).rejects.toMatchObject({ status: 401 })
    expect(handler).not.toHaveBeenCalled()
  })
})
