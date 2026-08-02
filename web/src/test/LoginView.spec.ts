import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

import LoginView from '@/views/LoginView.vue'

// Mock 后端 HTTP 客户端：登录/CSRF 请求不依赖真实后端
const httpMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('@/api/http', () => ({
  default: httpMock,
}))

let router: Router

/** 挂载登录页（挂在 /login 路由下）。 */
async function mountLogin(): Promise<VueWrapper> {
  const wrapper = mount(LoginView, {
    global: { plugins: [router] },
  })
  await flushPromises()
  return wrapper
}

describe('LoginView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    setActivePinia(createPinia())
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: LoginView },
        { path: '/', component: { template: '<div>home</div>' } },
      ],
    })
    // CSRF 端点默认成功
    httpMock.get.mockResolvedValue({ data: { token: 'test-csrf' } })
  })

  it('渲染登录表单', async () => {
    const wrapper = await mountLogin()

    expect(wrapper.find('[data-test="username"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="password"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="login-submit"]').exists()).toBe(true)
  })

  it('空输入时提示错误', async () => {
    const wrapper = await mountLogin()

    await wrapper.find('form').trigger('submit.prevent')

    expect(wrapper.find('[data-test="login-error"]').text()).toContain('请输入用户名和密码')
  })

  it('登录成功跳转工作台', async () => {
    httpMock.post.mockResolvedValue({ data: { username: 'admin' } })

    const wrapper = await mountLogin()
    await wrapper.find('[data-test="username"]').setValue('admin')
    await wrapper.find('[data-test="password"]').setValue('admin123')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/auth/login', { username: 'admin', password: 'admin123' })
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('登录失败显示错误提示', async () => {
    httpMock.post.mockRejectedValue(new Error('bad credentials'))

    const wrapper = await mountLogin()
    await wrapper.find('[data-test="username"]').setValue('admin')
    await wrapper.find('[data-test="password"]').setValue('wrong-password')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(wrapper.find('[data-test="login-error"]').text()).toContain('用户名或密码错误')
  })
})
