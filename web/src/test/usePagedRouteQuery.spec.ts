import { describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'

import { usePagedRouteQuery } from '@/shared/composables/usePagedRouteQuery'

function setupRoute() {
  const route = reactive({
    query: { page: '1', size: '20' } as Record<string, string>,
    fullPath: '/runs?page=1&size=20',
  })
  const replace = vi.fn(async (patch: { query?: Record<string, string> }) => {
    route.query = patch.query ?? {}
  })
  const router = { replace } as unknown as Router
  return {
    route: route as unknown as RouteLocationNormalizedLoaded,
    replace,
    router,
  }
}

describe('usePagedRouteQuery', () => {
  it('把筛选、页码和排序写回 URL', async () => {
    const { route, replace, router } = setupRoute()
    const { query, setQuery } = usePagedRouteQuery(router, route, {
      page: 1,
      size: 20,
      status: '',
      kind: '',
      keyword: '',
    })

    await setQuery({ page: 2, status: 'FAILED', keyword: 'patient' })

    expect(query.value).toMatchObject({ page: 2, status: 'FAILED', keyword: 'patient' })
    expect(replace).toHaveBeenCalledWith({
      query: expect.objectContaining({ page: '2', status: 'FAILED', keyword: 'patient' }),
    })
  })

  it('从 URL 解析数字和字符串查询参数', async () => {
    const { route, router } = setupRoute()
    route.query = { page: '3', size: '50', status: 'FAILED' }
    const { query } = usePagedRouteQuery(router, route, {
      page: 1,
      size: 20,
      status: '',
      kind: '',
      keyword: '',
    })

    expect(query.value).toMatchObject({ page: 3, size: 50, status: 'FAILED' })
    expect(query.value.kind).toBe('')
    expect(query.value.keyword).toBe('')
  })

  it('重置时省略默认值', async () => {
    const { replace, router, route } = setupRoute()
    const { setQuery } = usePagedRouteQuery(router, route, {
      page: 1,
      size: 20,
      status: '',
    })

    await setQuery({ status: '' })

    expect(replace).toHaveBeenCalledWith({ query: {} })
  })
})
