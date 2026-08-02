import axios, { type AxiosError } from 'axios'

import router from '@/router'
import { useSessionStore } from '@/stores/session'

/** 后端统一响应结构：code/message/requestId/details 为约定字段 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  requestId?: string
  details?: unknown
  data: T
}

/** 业务错误摘要：仅包含非敏感信息 */
export interface ApiErrorInfo {
  code?: number
  message: string
  requestId?: string
  details?: unknown
}

/**
 * Axios 基础客户端：
 * - baseURL 固定为后端 API 前缀 /api/v1
 * - withCredentials 携带会话 Cookie
 */
const http = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  timeout: 15000,
})

/**
 * 读取指定名称的 Cookie 值（解码 URL 编码）。
 * @param name Cookie 名
 */
function getCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(^|;\\s*)' + name + '=([^;]*)'))
  return match ? decodeURIComponent(match[2]) : null
}

// 请求拦截器：非 GET 请求自动附带 CSRF Token（XSRF-TOKEN Cookie → X-XSRF-TOKEN 请求头）
http.interceptors.request.use((config) => {
  const method = (config.method ?? 'get').toLowerCase()
  if (method !== 'get') {
    const token = getCookie('XSRF-TOKEN')
    if (token && !config.headers.get('X-XSRF-TOKEN')) {
      config.headers.set('X-XSRF-TOKEN', token)
    }
  }
  return config
})

// 响应拦截器：统一提取 code/message/requestId/details，并处理 401 会话失效
http.interceptors.response.use(
  (response) => {
    // 统一响应结构：将业务数据透出到 response.data 供调用方使用
    const body = response.data as ApiResponse | undefined
    if (body && typeof body.code === 'number') {
      response.data = body.data
    }
    return response
  },
  (error: AxiosError<ApiResponse>) => {
    // 401：会话失效，清空登录态并跳转登录页
    if (error.response?.status === 401) {
      const session = useSessionStore()
      session.clear()
      const current = router.currentRoute.value
      if (current.path !== '/login') {
        void router.push({ path: '/login', query: { redirect: current.fullPath } })
      }
    }

    // 仅输出非敏感的错误摘要（code/message/requestId），
    // 严禁在控制台打印 Token、密码或业务 Payload。
    const info = toApiErrorInfo(error)
    if (info.message) {
      console.error(
        `[http] 请求失败 code=${info.code ?? '-'} requestId=${info.requestId ?? '-'} message=${info.message}`,
      )
    }
    return Promise.reject(error)
  },
)

/**
 * 将 Axios 错误转换为业务错误摘要，仅保留非敏感字段。
 * @param error Axios 错误对象
 */
export function toApiErrorInfo(error: AxiosError<ApiResponse>): ApiErrorInfo {
  const body = error.response?.data
  if (body && typeof body.code === 'number') {
    return {
      code: body.code,
      message: body.message || `HTTP ${error.response?.status ?? '未知'}`,
      requestId: body.requestId,
      details: body.details,
    }
  }
  return { message: error.message }
}

export default http
