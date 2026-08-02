import { createRouter, createWebHistory, type Router } from 'vue-router'

import MainLayout from '@/layouts/MainLayout.vue'
import RunListView from '@/features/runs/RunListView.vue'
import RunDetailView from '@/views/RunDetailView.vue'
import TaskDetailView from '@/views/TaskDetailView.vue'
import SinkStatusView from '@/views/SinkStatusView.vue'
import { useSessionStore } from '@/stores/session'
import DashboardView from '@/views/DashboardView.vue'
import DataSourceView from '@/views/DataSourceView.vue'
import EndpointView from '@/views/EndpointView.vue'
import TaskCreateView from '@/views/TaskCreateView.vue'
import TaskListView from '@/views/TaskListView.vue'
import LoginView from '@/views/LoginView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题（用于占位组件展示） */
    title?: string
    /** 是否允许未登录访问 */
    public?: boolean
  }
}

/**
 * 应用路由表。
 * 除 /login 外均需登录；尚未开发的功能页使用“功能建设中”占位组件，
 * 后续任务会逐个替换为真实页面。
 */
const routes = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
  {
    path: '/',
    component: MainLayout,
    children: [
      { path: '', name: 'dashboard', component: DashboardView, meta: { title: '工作台' } },
      {
        path: 'data-sources',
        name: 'data-sources',
        component: DataSourceView,
        meta: { title: '数据源' },
      },
      { path: 'endpoints', name: 'endpoints', component: EndpointView, meta: { title: '端管理' } },
      { path: 'database', name: 'database', redirect: '/data-sources' },
      { path: 'tasks', name: 'tasks', component: TaskListView, meta: { title: '任务列表' } },
      { path: 'tasks/new', name: 'task-new', component: TaskCreateView, meta: { title: '新建任务' } },
      {
        path: 'tasks/:taskId/edit',
        name: 'task-edit',
        component: TaskCreateView,
        meta: { title: '编辑任务' },
      },
      {
        path: 'runs',
        name: 'runs',
        component: RunListView,
        meta: { title: '运行记录' },
      },
      { path: 'runs/:runId', name: 'run-detail', component: RunDetailView, meta: { title: '同步运行' } },
      { path: 'tasks/:taskId', name: 'task-detail', component: TaskDetailView, meta: { title: '任务详情' } },
      { path: 'system', name: 'system', component: SinkStatusView, meta: { title: '系统设置' } },
    ],
  },
  // 兜底：未知路径重定向到工作台
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

/**
 * 创建路由实例并注册登录守卫。
 * 提供工厂函数便于测试隔离（每个用例使用独立的 router 与 Pinia）。
 */
export function createAppRouter(): Router {
  const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes,
  })

  // 登录守卫：未登录只能访问 /login；已登录访问 /login 时跳转工作台
  router.beforeEach((to) => {
    const session = useSessionStore()
    if (!to.meta.public && !session.isLoggedIn) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (to.path === '/login' && session.isLoggedIn) {
      return { path: '/' }
    }
    return true
  })

  return router
}

// 应用全局使用的路由实例（http.ts 的 401 跳转等场景复用）
const router = createAppRouter()
export default router
