import { onBeforeUnmount, onMounted } from 'vue'
import { onBeforeRouteLeave, type RouteLocationNormalized } from 'vue-router'

import { confirm } from '@/shared/dialogs'

export function useUnsavedGuard(
  isDirty: () => boolean,
  options: { allowTo?: (to: RouteLocationNormalized) => boolean } = {},
): void {
  function onBeforeUnload(event: BeforeUnloadEvent): void {
    if (!isDirty()) return
    event.preventDefault()
    // Safari 与旧版 Chromium 只认 returnValue
    event.returnValue = ''
  }
  onMounted(() => window.addEventListener('beforeunload', onBeforeUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))
  onBeforeRouteLeave(async (to) => {
    if (!isDirty() || options.allowTo?.(to)) return true
    return confirm('有未保存的修改，离开后将丢失。确定离开吗？', '未保存')
  })
}
