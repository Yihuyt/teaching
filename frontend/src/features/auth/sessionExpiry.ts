import type { Pinia } from 'pinia'
import type { Router } from 'vue-router'

import { setUnauthorizedHandler } from '@/api/client'
import { useSessionStore } from '@/stores/session'

export function installSessionExpiryHandling(pinia: Pinia, router: Router): () => void {
  return setUnauthorizedHandler(() => {
    const session = useSessionStore(pinia)
    if (!session.authenticated) {
      return
    }
    const redirect = router.currentRoute.value.fullPath
    session.invalidate()
    void router.replace({
      name: 'login',
      query: { redirect },
    })
  })
}
