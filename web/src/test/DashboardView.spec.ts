import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import DashboardView from '@/views/DashboardView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('@/api/http', () => ({ default: httpMock }))

const SUMMARY = {
  source: { configured: true, product: 'OPEN_GAUSS', healthy: true, message: '连接正常' },
  sink: { configured: true, product: 'OPEN_GAUSS', healthy: true, message: 'Sink 就绪' },
  instance: {
    instanceId: '11111111-1111-1111-1111-111111111111',
    version: '0.1.0-SNAPSHOT',
    roles: 'source,sink',
    readiness: 'READY',
  },
  enabledTaskCount: 3,
  activeRunCount: 2,
  todaySuccessRate: 0.5,
  unresolvedFailureCount: 1,
  statisticsFrom: '2026-08-01T00:00:00+08:00',
  statisticsTo: '2026-08-02T00:00:00+08:00',
  recentRuns: [
    {
      runId: '22222222-2222-2222-2222-222222222222',
      taskId: '33333333-3333-3333-3333-333333333333',
      taskName: 'patient-sync',
      kind: 'INCREMENTAL',
      status: 'FAILED',
      startedAt: '2026-08-01T10:00:00Z',
      endedAt: '2026-08-01T10:05:00Z',
      sourceRowCount: 120,
      confirmedRowCount: 80,
    },
  ],
  alerts: [
    {
      runId: '22222222-2222-2222-2222-222222222222',
      taskId: '33333333-3333-3333-3333-333333333333',
      taskName: 'patient-sync',
      stage: 'TARGET_VALIDATION',
      summary: '目标字段类型不兼容',
      occurredAt: '2026-08-01T10:05:00Z',
      severity: 'ERROR',
    },
  ],
}

async function mountDashboard() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: DashboardView },
      { path: '/runs/:runId', name: 'run-detail', component: { template: '<div />' } },
      { path: '/runs', component: { template: '<div />' } },
      { path: '/tasks/new', component: { template: '<div />' } },
      { path: '/data-sources', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(DashboardView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('DashboardView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockResolvedValue({ data: SUMMARY })
  })

  it('展示统计口径和可行动异常', async () => {
    const wrapper = await mountDashboard()

    expect(wrapper.text()).toContain('今日成功率')
    expect(wrapper.text()).toContain('50%')
    expect(wrapper.text()).toContain('目标字段类型不兼容')
    expect(wrapper.find('[data-test="open-diagnosis"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="metric-enabled-tasks"]').text()).toContain('3')
    expect(wrapper.find('[data-test="metric-unresolved"]').text()).toContain('1')
  })

  it('成功率为空时显示暂无数据', async () => {
    httpMock.get.mockResolvedValue({
      data: { ...SUMMARY, todaySuccessRate: null, alerts: [], recentRuns: [] },
    })
    const wrapper = await mountDashboard()

    expect(wrapper.text()).toContain('暂无数据')
    expect(wrapper.text()).toContain('暂无待处理异常')
    expect(wrapper.text()).toContain('暂无运行记录')
  })
})
