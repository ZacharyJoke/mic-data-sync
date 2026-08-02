import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

import TaskListView from '@/views/TaskListView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))

vi.mock('@/api/http', () => ({ default: httpMock }))

const TASK = {
  taskId: '11111111-1111-1111-1111-111111111111',
  name: 'patient-sync',
  version: 1,
  lifecycleStatus: 'ENABLED',
  readMode: 'TABLE',
  readDefinition: {
    schema: 'public',
    table: 'patient',
    selectedColumns: ['id'],
    filters: [],
    paginationKeys: ['id'],
    updatedTimeField: 'updated_at',
  },
  targetSchema: 'public',
  targetTable: 'patient_copy',
  writeMode: 'UPSERT',
  uniqueKeys: ['id'],
  fieldMappings: [{ sourceField: 'id', targetField: 'id' }],
  remoteSinkUrl: 'http://sink:19090',
  expectedSinkInstanceId: null,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  latestRun: {
    runId: '22222222-2222-2222-2222-222222222222',
    kind: 'INCREMENTAL',
    status: 'FAILED',
    startedAt: '2026-08-01T10:00:00Z',
    endedAt: '2026-08-01T10:05:00Z',
  },
}

async function mountTaskList(path = '/tasks'): Promise<{ wrapper: VueWrapper; router: Router }> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tasks', component: TaskListView },
      { path: '/tasks/:taskId', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()

  const wrapper = mount(TaskListView, {
    global: {
      plugins: [router, ElementPlus],
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('TaskListView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockResolvedValue({
      data: { items: [TASK], total: 1, page: 1, size: 20 },
    })
    httpMock.post.mockResolvedValue({ data: TASK })
  })

  it('显示任务高密度表格并直接使用 latestRun 摘要', async () => {
    const { wrapper } = await mountTaskList()

    expect(wrapper.find('[data-test="task-table"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('patient-sync')
    expect(wrapper.text()).toContain('INCREMENTAL')
    expect(httpMock.get).toHaveBeenCalledTimes(1)
  })

  it('把任务筛选写入 URL', async () => {
    const { wrapper, router } = await mountTaskList()
    const replaceSpy = vi.spyOn(router, 'replace')

    await wrapper.find('[data-test="task-keyword"]').setValue('patient')
    await wrapper.find('[data-test="task-filter-submit"]').trigger('click')
    await flushPromises()

    expect(replaceSpy).toHaveBeenCalledWith({
      query: expect.objectContaining({ keyword: 'patient' }),
    })
  })

  it('启用任务显示暂停和禁用动作并调用接口', async () => {
    const { wrapper } = await mountTaskList()

    expect(wrapper.find('[data-test="task-pause"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="task-disable"]').exists()).toBe(true)

    await wrapper.find('[data-test="task-pause"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith(`/tasks/${TASK.taskId}/pause`)
  })

  it('暂停任务显示继续动作', async () => {
    httpMock.get.mockResolvedValue({
      data: { items: [{ ...TASK, lifecycleStatus: 'PAUSED' }], total: 1, page: 1, size: 20 },
    })
    const { wrapper } = await mountTaskList()

    expect(wrapper.find('[data-test="task-resume"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="task-pause"]').exists()).toBe(false)
  })
})
