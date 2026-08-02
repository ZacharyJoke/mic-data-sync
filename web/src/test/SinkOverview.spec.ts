import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import SinkOverview from '@/components/sink/SinkOverview.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
const messageMock = vi.hoisted(() => ({ warning: vi.fn(), success: vi.fn(), error: vi.fn() }))

vi.mock('@/api/http', () => ({ default: httpMock }))
vi.mock('element-plus', () => ({ ElMessage: messageMock }))

const STATUS = {
  sinkInstanceId: '11111111-1111-1111-1111-111111111111',
  startupId: '22222222-2222-2222-2222-222222222222',
  databaseType: 'OPEN_GAUSS',
  capabilityStatus: 'READY',
  message: null,
  dbaSql: null,
  protocolVersion: 1,
  batchLimits: { maxRowsPerBatch: 1000, maxPayloadBytes: 16777216 },
  tlsInsecureSkipVerify: false,
}

const ENDPOINTS = [
  {
    id: 'self-sink',
    name: '本地 Sink 端',
    role: 'SINK',
    baseUrl: 'http://127.0.0.1:19090',
    instanceId: '11111111-1111-1111-1111-111111111111',
    isSelf: true,
    status: 'READY',
    lastProbeAt: '2026-08-02T09:17:00Z',
    createdAt: '2026-08-02T00:00:00Z',
    updatedAt: '2026-08-02T00:00:00Z',
  },
  {
    id: 'sink-b',
    name: 'Sink-B-19091',
    role: 'SINK',
    baseUrl: 'http://127.0.0.1:19091',
    instanceId: '33333333-3333-3333-3333-333333333333',
    isSelf: false,
    status: 'READY',
    lastProbeAt: '2026-08-02T09:17:02Z',
    createdAt: '2026-08-02T00:00:00Z',
    updatedAt: '2026-08-02T00:00:00Z',
  },
]

describe('SinkOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url.includes('/status')) {
        return Promise.resolve({ data: STATUS })
      }
      if (url.includes('/sink-token')) {
        return Promise.resolve({ data: { configured: true, display: 'mic_****abcd' } })
      }
      if (url.includes('/endpoints')) {
        return Promise.resolve({ data: ENDPOINTS })
      }
      return Promise.resolve({ data: {} })
    })
    httpMock.post.mockImplementation((url: string) => {
      if (url.includes('/auth-check')) {
        return Promise.resolve({
          data: { ok: true, message: '认证通过', sourceDisplay: 'mic_****abcd', sinkMasked: 'mic_****abcd', handshake: 'HTTP 200' },
        })
      }
      return Promise.resolve({ data: { message: '探活成功', endpoint: ENDPOINTS[0] } })
    })
  })

  it('按 Sink 端展示状态、类型与批次限制，时间统一格式', async () => {
    const wrapper = mount(SinkOverview)
    await flushPromises()

    expect(wrapper.text()).toContain('本地 Sink 端')
    expect(wrapper.text()).toContain('Sink-B-19091')
    expect(wrapper.text()).toContain('OPEN_GAUSS')
    expect(wrapper.text()).toContain('1000 行 / 16 MB')
    expect(wrapper.text()).toContain('2026-08-02 17:17:00')
    expect(wrapper.text()).not.toContain('刷新令牌')
  })

  it('探活调用对应端接口', async () => {
    const wrapper = mount(SinkOverview)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '探活')?.trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/endpoints/self-sink/probe')
  })

  it('批次认证显示结果', async () => {
    const wrapper = mount(SinkOverview)
    await flushPromises()

    const buttons = wrapper.findAll('button').filter((button) => button.text() === '批次认证')
    await buttons[1].trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/endpoints/sink-b/auth-check')
    expect(wrapper.text()).toContain('认证通过')
  })
})
