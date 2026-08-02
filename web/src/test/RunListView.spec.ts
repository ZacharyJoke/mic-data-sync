import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import RunListView from '@/features/runs/RunListView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('@/api/http', () => ({ default: httpMock }))

const TASK = {
  taskId: '22222222-2222-2222-2222-222222222222',
  name: 'patient-sync',
  version: 1,
  lifecycleStatus: 'ENABLED',
  readMode: 'TABLE',
  readDefinition: null,
  targetSchema: 'public',
  targetTable: 'patient_copy',
  writeMode: 'UPSERT',
  uniqueKeys: ['id'],
  fieldMappings: [],
  remoteSinkUrl: 'http://sink:19090',
  expectedSinkInstanceId: null,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
}

const RUN = {
  runId: '11111111-1111-1111-1111-111111111111',
  taskId: TASK.taskId,
  taskName: 'patient-sync',
  kind: 'INCREMENTAL',
  status: 'FAILED',
  pauseReason: '目标字段类型不兼容',
  startedAt: '2026-08-01T10:00:00Z',
  endedAt: '2026-08-01T10:05:00Z',
  sourceRowCount: 120,
  confirmedRowCount: 80,
}

async function mountView(path: string): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/runs', component: RunListView },
      { path: '/runs/:runId', name: 'run-detail', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()

  const wrapper = mount(RunListView, {
    global: {
      plugins: [router, ElementPlus],
    },
  })
  await flushPromises()
  return wrapper
}

describe('RunListView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/tasks') {
        return Promise.resolve({
          data: { items: [TASK], total: 1, page: 1, size: 100 },
        })
      }
      if (url === '/runs') {
        return Promise.resolve({
          data: { items: [RUN], total: 1, page: 2, size: 20 },
        })
      }
      return Promise.reject(new Error(`unexpected url ${url}`))
    })
  })

  it('显示高密度运行表格并保留诊断返回地址', async () => {
    const wrapper = await mountView('/runs?page=2&status=FAILED')

    expect(wrapper.find('[data-test="run-table"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('patient-sync')
    expect(wrapper.find('[data-test="run-row-link"]').attributes('href')).toContain(
      'returnTo=/runs?page=2%26status=FAILED',
    )
  })

  it('把 URL 筛选传给运行列表接口', async () => {
    await mountView('/runs?page=2&status=FAILED&kind=INCREMENTAL&keyword=patient')

    const calls = httpMock.get.mock.calls.filter(([url]) => url === '/runs')
    expect(calls.length).toBeGreaterThan(0)
    expect(calls.at(-1)?.[1].params).toMatchObject({
      page: 2,
      status: 'FAILED',
      kind: 'INCREMENTAL',
      keyword: 'patient',
    })
  })
})
