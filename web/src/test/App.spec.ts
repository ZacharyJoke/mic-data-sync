import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { nextTick } from 'vue'
import type { Router } from 'vue-router'

import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { SESSION_STORAGE_KEY } from '@/stores/session'

// Mock 后端 HTTP 客户端：未登录用例挂载登录页会触发 CSRF 请求，避免真实网络调用
vi.mock('@/api/http', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

/** 当前用例使用的 Pinia 与路由实例（均在 beforeEach 中重建，保证用例隔离） */
let pinia: Pinia
let router: Router

/**
 * 挂载应用壳：注册 Pinia 与路由，并导航到工作台。
 * 返回挂载后的组件包装器。
 */
async function mountApp(): Promise<VueWrapper> {
  const wrapper = mount(App, {
    global: {
      plugins: [pinia, router],
    },
  })
  await router.push('/')
  await router.isReady()
  await nextTick()
  return wrapper
}

describe('应用壳', () => {
  beforeEach(async () => {
    // 清理登录态并重建 Pinia、路由，避免用例之间相互影响
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    router = createAppRouter()
    await router.replace('/login')
    await router.isReady()
  })

  it('未登录时显示登录页', async () => {
    const wrapper = await mountApp()

    expect(wrapper.find('[data-test="login-view"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="main-layout"]').exists()).toBe(false)
  })

  it('登录后显示主布局和路由出口', async () => {
    // 模拟已登录会话：预置持久化数据后重建 Pinia 与路由，
    // store 首次实例化时从 localStorage 恢复登录态
    localStorage.setItem(
      SESSION_STORAGE_KEY,
      JSON.stringify({ username: 'tester', displayName: '测试用户' }),
    )
    pinia = createPinia()
    setActivePinia(pinia)
    router = createAppRouter()

    const wrapper = await mountApp()

    expect(wrapper.find('[data-test="main-layout"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="dashboard-view"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test="primary-nav-item"]')).toHaveLength(6)
    expect(wrapper.text()).toContain('运行记录')
  })
})
