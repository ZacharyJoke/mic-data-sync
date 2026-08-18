import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

import TaskDetailView from '@/views/TaskDetailView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('@/api/http', () => ({ default: httpMock }))

const TASK = {
  taskId: '11111111-1111-1111-1111-111111111111',
  name: 'patient-sync',
  version: 1,
  lifecycleStatus: 'ENABLED',
  readMode: 'SQL',
  readDefinition: {
    rawSql: 'SELECT id FROM public.patient',
    baseTable: 'public.patient',
    resultColumns: ['id'],
    structureFingerprint: 'patient-v1',
    paginationKeys: ['id'],
    updatedTimeField: 'updated_at',
  },
  targetSchema: 'public',
  targetTable: 'patient_copy',
  writeMode: 'UPSERT',
  uniqueKeys: ['id'],
  fieldMappings: [{ sourceField: 'id', targetField: 'id' }],
  remoteSinkUrl: 'http://sink:19090/mic-data-sync',
  expectedSinkInstanceId: null,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  latestRun: null,
}

async function mountTaskDetail(path: string): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/tasks/:taskId', component: TaskDetailView }],
  })
  await router.push(path)
  await router.isReady()

  const wrapper = mount(TaskDetailView, {
    global: {
      plugins: [router, ElementPlus],
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('TaskDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/tasks/11111111-1111-1111-1111-111111111111') {
        return Promise.resolve({ data: TASK })
      }
      if (url === '/tasks/11111111-1111-1111-1111-111111111111/runs') {
        return Promise.resolve({ data: { items: [], total: 0, page: 1, size: 20 } })
      }
      if (url === '/target/metadata/public/patient_copy') {
        return Promise.resolve({
          data: { schema: 'public', table: 'patient_copy', columns: [], primaryKeyColumns: [], uniqueIndexes: [] },
        })
      }
      return Promise.reject(new Error(`unexpected url ${url}`))
    })
  })

  it('使用 tab 查询参数切换概览、配置和运行历史', async () => {
    const { wrapper, router } = await mountTaskDetail(
      '/tasks/11111111-1111-1111-1111-111111111111?tab=config',
    )
    const replaceSpy = vi.spyOn(router, 'replace')

    expect(wrapper.find('[data-test="task-config-tab"]').isVisible()).toBe(true)

    await wrapper.find('[data-test="history-tab-trigger"]').trigger('click')
    await flushPromises()

    expect(replaceSpy).toHaveBeenCalledWith({
      query: expect.objectContaining({ tab: 'history' }),
    })
  })

  it('概览展示最近运行摘要', async () => {
    const { wrapper } = await mountTaskDetail(
      '/tasks/11111111-1111-1111-1111-111111111111',
    )

    expect(wrapper.find('[data-test="task-overview-tab"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无运行')
  })

  it('启用任务展示暂停与禁用动作', async () => {
    const { wrapper } = await mountTaskDetail(
      '/tasks/11111111-1111-1111-1111-111111111111',
    )

    expect(wrapper.find('[data-test="task-pause"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="task-disable"]').exists()).toBe(true)
  })
})
