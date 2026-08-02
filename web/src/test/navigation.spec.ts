import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import {
  createMemoryHistory,
  createRouter,
  type RouteRecordRaw,
  type Router,
} from 'vue-router'

import App from '@/App.vue'
import MainLayout from '@/layouts/MainLayout.vue'
import { createAppRouter } from '@/router'
import { SESSION_STORAGE_KEY } from '@/stores/session'

const authenticatedSession = JSON.stringify({
  username: 'tester',
  displayName: '测试用户',
})

function testView(testId: string) {
  return {
    template: `<section data-test="${testId}">${testId}</section>`,
  }
}

const layoutRoutes: RouteRecordRaw[] = [
  { path: '/login', component: testView('login-view') },
  {
    path: '/',
    component: MainLayout,
    children: [
      { path: '', component: testView('dashboard-view'), meta: { title: '工作台' } },
      { path: 'data-sources', component: testView('data-sources-view') },
      { path: 'tasks', component: testView('tasks-view') },
      { path: 'tasks/new', component: testView('task-create-view') },
      { path: 'tasks/:taskId', component: testView('task-detail-view') },
      { path: 'runs', component: testView('runs-view') },
      { path: 'runs/:runId', component: testView('run-detail-view') },
      { path: 'system', component: testView('system-view') },
    ],
  },
]

interface MountedLayout {
  pinia: Pinia
  router: Router
  wrapper: VueWrapper
}

async function mountLayout(path = '/'): Promise<MountedLayout> {
  localStorage.setItem(SESSION_STORAGE_KEY, authenticatedSession)
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: layoutRoutes,
  })
  await router.push(path)
  await router.isReady()

  const wrapper = mount(App, {
    global: {
      plugins: [pinia, router],
    },
  })
  await flushPromises()
  return { pinia, router, wrapper }
}

describe('路由兼容性', () => {
  it('旧命名路由和旧路径均重定向到数据源', async () => {
    localStorage.setItem(SESSION_STORAGE_KEY, authenticatedSession)
    setActivePinia(createPinia())
    const router = createAppRouter()

    await router.push({ name: 'database' })
    expect(router.currentRoute.value.path).toBe('/data-sources')

    await router.push('/database')
    expect(router.currentRoute.value.path).toBe('/data-sources')
  })
})

describe('主导航行为', () => {
  it('点击运行记录后更新 URL、页面和当前导航状态', async () => {
    const { router, wrapper } = await mountLayout()
    const runsLink = wrapper.find('[data-test="primary-nav-item"][href="/runs"]')

    expect(runsLink.exists()).toBe(true)
    await runsLink.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/runs')
    expect(wrapper.find('[data-test="runs-view"]').exists()).toBe(true)
    expect(runsLink.attributes('aria-current')).toBe('page')
  })

  it.each([
    ['/tasks/new', '/tasks'],
    ['/tasks/task-1', '/tasks'],
    ['/runs/run-1', '/runs'],
  ])('访问 %s 时激活父级导航 %s', async (path, parentHref) => {
    const { wrapper } = await mountLayout(path)
    const parentLink = wrapper.find(
      `[data-test="primary-nav-item"][href="${parentHref}"]`,
    )

    expect(parentLink.attributes('aria-current')).toBe('page')
  })

  it('退出时清理会话并跳转登录页', async () => {
    const { router, wrapper } = await mountLayout()

    await wrapper.find('[data-test="logout-button"]').trigger('click')
    await flushPromises()

    expect(localStorage.getItem(SESSION_STORAGE_KEY)).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper.find('[data-test="login-view"]').exists()).toBe(true)
  })
})
