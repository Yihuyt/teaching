import type { LocationQuery } from 'vue-router'

export function tabFromQuery<T extends string>(query: LocationQuery, tabs: ReadonlySet<T>, fallback: T): T {
  const raw = query.tab
  return typeof raw === 'string' && tabs.has(raw as T) ? (raw as T) : fallback
}
