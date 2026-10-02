import { defineStore } from 'pinia'

import { api, clearCsrfToken, isUnauthorized } from '@/api/client'
import {
  AccountViewRole,
  type AccountView,
  type LoginRequest,
  type ChangePasswordRequest,
} from '@/api/generated'

interface SessionState {
  account: AccountView | null
  initialized: boolean
}

export const useSessionStore = defineStore('session', {
  state: (): SessionState => ({
    account: null,
    initialized: false,
  }),
  getters: {
    authenticated: (state): boolean => state.account !== null,
    canEnterManagement: (state): boolean =>
      state.account !== null &&
      (state.account.role === AccountViewRole.root ||
        state.account.role === AccountViewRole.admin ||
        state.account.role === AccountViewRole.teacher),
    isPlatformAdmin: (state): boolean =>
      state.account !== null &&
      (state.account.role === AccountViewRole.root || state.account.role === AccountViewRole.admin),
    isRoot: (state): boolean => state.account?.role === AccountViewRole.root,
  },
  actions: {
    async initialize(): Promise<void> {
      if (this.initialized) {
        return
      }
      try {
        const response = await api.sessionCurrent()
        this.account = response.data
        this.initialized = true
      } catch (error: unknown) {
        if (!isUnauthorized(error)) {
          throw error
        }
        this.account = null
        this.initialized = true
      }
    },
    async login(credentials: LoginRequest): Promise<AccountView> {
      const response = await api.sessionLogin(credentials)
      clearCsrfToken()
      this.account = response.data
      this.initialized = true
      return response.data
    },
    async logout(): Promise<void> {
      await api.sessionLogout()
      clearCsrfToken()
      this.account = null
      this.initialized = true
    },
    invalidate(): void {
      clearCsrfToken()
      this.account = null
      this.initialized = true
    },
    async changePassword(payload: ChangePasswordRequest): Promise<void> {
      await api.accountChangePassword(payload)
      clearCsrfToken()
      this.account = null
      this.initialized = true
    },
  },
})
