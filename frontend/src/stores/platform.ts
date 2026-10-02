import { defineStore } from 'pinia'

import { api } from '@/api/client'
import type { PlatformView } from '@/api/generated'

interface PlatformState {
  settings: PlatformView | null
  initialized: boolean
}

export const usePlatformStore = defineStore('platform', {
  state: (): PlatformState => ({
    settings: null,
    initialized: false,
  }),
  getters: {
    siteName(state): string {
      if (!state.settings) {
        throw new Error('平台公开设置尚未加载')
      }
      return state.settings.siteName
    },
    footerText(state): string {
      if (!state.settings) {
        throw new Error('平台公开设置尚未加载')
      }
      return state.settings.footerText
    },
  },
  actions: {
    async initialize(): Promise<void> {
      if (this.initialized) {
        return
      }
      const response = await api.platformPublicSettings()
      this.replace(response.data)
    },
    replace(settings: PlatformView): void {
      this.settings = settings
      this.initialized = true
    },
  },
})
