import axios, { AxiosHeaders, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'

import { getApi, type CsrfToken } from '@/api/generated'

const MUTATING_METHODS = new Set(['post', 'put', 'patch', 'delete'])

interface ProblemDetails {
  type: string
  title: string
  status: number
  detail: string
  instance?: string
  requestId?: string
}

export class ApiProblem extends Error {
  readonly status: number
  readonly type: string
  readonly instance: string | undefined
  /** 服务端为这次请求分配的编号,出现在服务端日志里,用于排查 */
  readonly requestId: string | undefined

  constructor(problem: ProblemDetails) {
    super(problem.detail)
    this.name = 'ApiProblem'
    this.status = problem.status
    this.type = problem.type
    this.instance = problem.instance
    this.requestId = problem.requestId
  }
}

let csrfToken: CsrfToken | undefined
let csrfRequest: Promise<CsrfToken> | undefined
let unauthorizedHandler: (() => void) | undefined

export const http: AxiosInstance = axios.create({
  baseURL: '/',
  withCredentials: true,
  headers: {
    Accept: 'application/json',
  },
})

function assertCsrfToken(value: unknown): asserts value is CsrfToken {
  if (
    typeof value !== 'object' ||
    value === null ||
    !('headerName' in value) ||
    typeof value.headerName !== 'string' ||
    value.headerName.length === 0 ||
    !('token' in value) ||
    typeof value.token !== 'string' ||
    value.token.length === 0
  ) {
    throw new Error('服务端返回了无效的 CSRF 令牌')
  }
}

async function loadCsrfToken(): Promise<CsrfToken> {
  if (csrfToken) {
    return csrfToken
  }
  if (!csrfRequest) {
    csrfRequest = http.get<CsrfToken>('/api/v1/session/csrf').then((response) => {
      assertCsrfToken(response.data)
      csrfToken = response.data
      return response.data
    })
  }
  try {
    return await csrfRequest
  } finally {
    csrfRequest = undefined
  }
}

function isProblemDetails(value: unknown): value is ProblemDetails {
  return (
    typeof value === 'object' &&
    value !== null &&
    'type' in value &&
    typeof value.type === 'string' &&
    'title' in value &&
    typeof value.title === 'string' &&
    'status' in value &&
    typeof value.status === 'number' &&
    'detail' in value &&
    typeof value.detail === 'string'
  )
}

function normalizeError(error: unknown): never {
  if (axios.isAxiosError(error) && isProblemDetails(error.response?.data)) {
    throw new ApiProblem(error.response.data)
  }
  if (axios.isAxiosError(error) && !error.response) {
    throw new Error('无法连接教学平台服务，请确认服务已经启动')
  }
  if (axios.isAxiosError(error) && error.response) {
    throw new Error('服务返回的数据格式错误，请联系管理员')
  }
  throw error
}

function shouldInvalidateSession(error: unknown): boolean {
  if (!axios.isAxiosError(error) || error.response?.status !== 401) {
    return false
  }
  const path = error.config?.url
  return path !== '/api/v1/session/login' && path !== '/api/v1/session'
}

function attachCsrfHeader(config: InternalAxiosRequestConfig, token: CsrfToken): void {
  const headers = AxiosHeaders.from(config.headers)
  headers.set(token.headerName, token.token)
  config.headers = headers
}

http.interceptors.request.use(async (config) => {
  const method = config.method?.toLowerCase()
  if (method && MUTATING_METHODS.has(method)) {
    attachCsrfHeader(config, await loadCsrfToken())
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (shouldInvalidateSession(error)) {
      clearCsrfToken()
      unauthorizedHandler?.()
    }
    return normalizeError(error)
  },
)

export const api = getApi(http)

export function clearCsrfToken(): void {
  csrfToken = undefined
  csrfRequest = undefined
}

export async function csrfHeaderEntry(): Promise<[string, string]> {
  const token = await loadCsrfToken()
  if (!token.headerName || !token.token) {
    throw new Error('服务端返回了无效的 CSRF 令牌')
  }
  return [token.headerName, token.token]
}

export function setUnauthorizedHandler(handler: (() => void) | undefined): () => void {
  unauthorizedHandler = handler
  return () => {
    if (unauthorizedHandler === handler) {
      unauthorizedHandler = undefined
    }
  }
}

export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiProblem && error.status === 401
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiProblem && error.requestId) {
    return `${error.message}（错误编号 ${error.requestId}）`
  }
  if (error instanceof ApiProblem || error instanceof Error) {
    return error.message
  }
  return '发生了无法识别的错误'
}
