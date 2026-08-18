import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

import RunDetailView from '@/views/RunDetailView.vue'
import { formatDateTime } from '@/shared/utils/format'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
const messageMock = vi.hoisted(() => ({ warning: vi.fn(), success: vi.fn(), error: vi.fn() }))
const routerPush = vi.hoisted(() => vi.fn())

vi.mock('@/api/http', () => ({ default: httpMock }))
vi.mock('element-plus', () => ({
  ElMessage: messageMock,
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(true), alert: vi.fn() },
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { runId: '11111111-1111-1111-1111-111111111111' },
    query: {},
    fullPath: '/runs/11111111-1111-1111-1111-111111111111',
  }),
  useRouter: () => ({ push: routerPush }),
}))

const RUN = {
  runId: '11111111-1111-1111-1111-111111111111',
  taskId: '22222222-2222-2222-2222-222222222222',
  taskName: 'patient-sync',
  kind: 'INITIAL_FULL',
  status: 'RUNNING',
  pauseReason: null,
  startedAt: '2026-08-01T00:00:00Z',
  endedAt: null,
  sourceRowCount: 100,
  confirmedRowCount: 80,
}

let wrapper: VueWrapper | undefined

async function mountView() {
  wrapper = mount(RunDetailView, {
    global: {
      stubs: {
        'router-link': { template: '<a><slot /></a>' },
        'el-button': { template: '<button type="button"><slot /></button>' },
        'el-pagination': true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('RunDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url.includes('/diagnosis')) {
        return Promise.resolve({ data: null })
      }
      if (url.includes('/actions')) {
        return Promise.resolve({
          data: { runId: RUN.runId, actions: ACTIVE_ACTIONS },
        })
      }
      if (url.includes('/batches')) {
        return Promise.resolve({ data: { items: [], total: 0, page: 1, size: 20 } })
      }
      return Promise.resolve({ data: RUN })
    })
  })

  afterEach(async () => {
    await wrapper?.unmount()
    wrapper = undefined
  })

  it('渲染运行状态与统计', async () => {
    const view = await mountView()

    expect(view.find('[data-test="run-detail"]').exists()).toBe(true)
    expect(view.text()).toContain('运行中')
    expect(view.text()).toContain('100 行')
    expect(view.text()).toContain('80 行')
  })

  it('运行中显示暂停按钮', async () => {
    const view = await mountView()

    expect(view.find('[data-test="pause-action"]').exists()).toBe(true)
  })

  it('批次列表空态提示', async () => {
    const view = await mountView()

    expect(view.find('[data-test="batch-list"]').text()).toContain('暂无批次明细')
  })

  it('批次列表渲染时间水位列', async () => {
    httpMock.get.mockImplementation((url: string) => {
      if (url.includes('/diagnosis')) {
        return Promise.resolve({ data: null })
      }
      if (url.includes('/actions')) {
        return Promise.resolve({
          data: { runId: RUN.runId, actions: ACTIVE_ACTIONS },
        })
      }
      if (url.includes('/batches')) {
        return Promise.resolve({
          data: {
            items: [
              {
                batchId: 'batch-1',
                runId: RUN.runId,
                batchSequence: 1,
                payloadHash: 'abc123',
                rowCount: 5,
                timeWatermark: '2026-10-01T10:00:00Z',
                status: 'SUCCEEDED',
                attemptCount: 1,
                createdAt: '2026-08-01T00:00:00Z',
              },
            ],
            total: 1,
            page: 1,
            size: 20,
          },
        })
      }
      return Promise.resolve({ data: RUN })
    })
    const view = await mountView()

    expect(view.find('[data-test="batch-list"]').text()).toContain('时间水位')
    expect(view.find('[data-test="batch-list"]').text()).toContain(
      formatDateTime('2026-10-01T10:00:00Z'),
    )
  })

  it('暂停操作调用接口并刷新', async () => {
    httpMock.post.mockResolvedValue({
      data: { accepted: true, resourceId: RUN.runId, status: 'PAUSED', message: '已暂停' },
    })
    const view = await mountView()

    await view.find('[data-test="pause-action"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/runs/11111111-1111-1111-1111-111111111111/pause')
  })

  it('只显示后端允许的动作并阻止重复提交', async () => {
    const actionsResponse = [
      { type: 'REVALIDATE', enabled: true, reason: '' },
      { type: 'RETRY', enabled: true, reason: '' },
      { type: 'RESUME', enabled: false, reason: '仅已暂停的 Run 可继续' },
    ]
    httpMock.get.mockImplementation((url: string) => {
      if (url.includes('/actions')) {
        return Promise.resolve({ data: { runId: RUN.runId, actions: actionsResponse } })
      }
      if (url.includes('/batches')) {
        return Promise.resolve({ data: { items: [], total: 0, page: 1, size: 20 } })
      }
      return Promise.resolve({ data: { ...RUN, status: 'FAILED' } })
    })
    httpMock.post.mockResolvedValue({
      data: { accepted: true, resourceId: 'new-run-id', status: 'RUNNING', message: 'ok' },
    })
    const view = await mountView()

    expect(view.text()).toContain('重新校验')
    expect(view.text()).toContain('安全重试')
    expect(view.find('[data-test="resume-action"]').exists()).toBe(false)

    const retry = view.find('[data-test="retry-action"]')
    await Promise.all([retry.trigger('click'), retry.trigger('click')])

    expect(httpMock.post).toHaveBeenCalledTimes(1)
  })
})

const ACTIVE_ACTIONS = [
  { type: 'PAUSE', enabled: true, reason: '' },
  { type: 'RESUME', enabled: false, reason: '仅已暂停的 Run 可继续' },
  { type: 'REVALIDATE', enabled: false, reason: '仅失败运行需要重新校验' },
  { type: 'RETRY', enabled: false, reason: '该失败不是可安全重试类型' },
]
