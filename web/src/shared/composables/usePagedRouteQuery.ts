import { computed } from 'vue'
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'

/**
 * 将列表筛选、页码和分页大小同步到 URL，刷新后保持查询状态。
 */
export function usePagedRouteQuery<T extends Record<string, string | number>>(
  router: Router,
  route: RouteLocationNormalizedLoaded,
  defaults: T,
) {
  const query = computed<T>(() => {
    const value = { ...defaults }
    for (const key of Object.keys(defaults) as Array<keyof T>) {
      const raw = route.query[String(key)]
      if (raw === undefined || Array.isArray(raw)) {
        continue
      }
      value[key] = (typeof defaults[key] === 'number' ? Number(raw) : raw) as T[keyof T]
    }
    return value
  })

  async function setQuery(patch: Partial<T>) {
    const next = { ...query.value, ...patch }
    const serialized = Object.fromEntries(
      Object.entries(next)
        .filter(([key, value]) => value !== defaults[key] && value !== '')
        .map(([key, value]) => [key, String(value)]),
    )
    await router.replace({ query: serialized })
  }

  return { query, setQuery }
}
