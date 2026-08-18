import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

import { useSessionStore } from '@/stores/session'

// Mock 后端 HTTP 客户端：不依赖真实后端
const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('@/api/http', () => ({
  default: httpMock,
}))

describe('session store CSRF Cookie 名', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('ensureCsrfToken 保存后端下发的动态 Cookie 名', async () => {
    httpMock.get.mockResolvedValue({ data: { token: 't1', csrfCookieName: 'MIC-A-CSRF' } })

    const session = useSessionStore()
    expect(session.csrfCookieName).toBeNull()

    await session.ensureCsrfToken()
    expect(session.csrfCookieName).toBe('MIC-A-CSRF')
  })

  it('后端未返回 Cookie 名时回退默认 XSRF-TOKEN', async () => {
    httpMock.get.mockResolvedValue({ data: { token: 't1' } })

    const session = useSessionStore()
    await session.ensureCsrfToken()
    expect(session.csrfCookieName).toBe('XSRF-TOKEN')
  })
})
