import type { AccountViewRole } from '@/api/generated'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    title?: string
    roles?: AccountViewRole[]
  }
}
