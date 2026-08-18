import { computed, ref } from 'vue'

import http from '@/api/http'
import { defineStore } from 'pinia'

/** 会话持久化使用的 localStorage 键 */
export const SESSION_STORAGE_KEY = 'mic-data-sync.session'

/** 当前登录用户信息（MVP 阶段仅包含基础字段） */
export interface SessionUser {
  username: string
  displayName?: string
}

/** 从 localStorage 恢复登录态；解析失败或格式不合法时视为未登录。 */
function restoreSession(): SessionUser | null {
  try {
    const raw = window.localStorage.getItem(SESSION_STORAGE_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as SessionUser
    return typeof parsed?.username === 'string' && parsed.username.length > 0 ? parsed : null
  } catch {
    return null
  }
}

/**
 * 登录态 Store。
 * 登录/退出调用真实后端接口（Cookie Session）；登录态以 localStorage 缓存支撑
 * 路由守卫与会话恢复，服务端 401 时由 http.ts 拦截器清空会话并跳转登录页。
 */
export const useSessionStore = defineStore('session', () => {
  const user = ref<SessionUser | null>(restoreSession())

  /** 是否已登录 */
  const isLoggedIn = computed(() => user.value !== null)

  /** 是否已获取 CSRF Token（避免重复请求） */
  let csrfReady = false

  /**
   * 当前实例的 CSRF Cookie 名（后端 /csrf 下发，默认 XSRF-TOKEN）。
   * 同主机多实例时各实例 Cookie 名不同，必须动态读取，不能写死。
   */
  const csrfCookieName = ref<string | null>(null)

  /**
   * 获取 CSRF Token：写入 XSRF-TOKEN Cookie，后续 POST 由拦截器自动附带。
   */
  async function ensureCsrfToken(): Promise<void> {
    if (csrfReady) {
      return
    }
    const response = await http.get<{ token: string; csrfCookieName?: string }>('/auth/csrf')
    csrfCookieName.value = response.data?.csrfCookieName || 'XSRF-TOKEN'
    csrfReady = true
  }

  /**
   * 登录：调用后端登录接口并建立本地会话。
   * @throws 登录失败（用户名或密码错误）时抛出异常
   */
  async function login(username: string, password: string): Promise<SessionUser> {
    await ensureCsrfToken()
    const response = await http.post<{ username: string }>('/auth/login', { username, password })
    const name = response.data?.username ?? username
    const nextUser: SessionUser = { username: name, displayName: name }
    user.value = nextUser
    try {
      window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(nextUser))
    } catch {
      // localStorage 不可用时仅保留内存态
    }
    return nextUser
  }

  /** 退出登录：调用后端接口并清空本地会话。 */
  async function logout(): Promise<void> {
    try {
      await http.post('/auth/logout')
    } finally {
      clear()
    }
  }

  /** 清空登录态并移除持久化数据。 */
  function clear() {
    user.value = null
    try {
      window.localStorage.removeItem(SESSION_STORAGE_KEY)
    } catch {
      // 清理失败不阻塞业务
    }
  }

  return { user, isLoggedIn, csrfCookieName, ensureCsrfToken, login, logout, clear }
})
