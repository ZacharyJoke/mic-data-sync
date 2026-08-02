import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import EndpointView from '@/views/EndpointView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
const messageMock = vi.hoisted(() => ({ warning: vi.fn(), success: vi.fn(), error: vi.fn() }))
const confirmMock = vi.hoisted(() => vi.fn().mockResolvedValue(true))
const sinkApiMock = vi.hoisted(() => ({
  rotateSinkToken: vi.fn().mockResolvedValue({
    configured: true,
    display: 'mic_****1234',
    generated: 'mic_newtoken1234',
  }),
}))

vi.mock('@/api/http', () => ({ default: httpMock }))
vi.mock('@/api/sink', () => sinkApiMock)
vi.mock('element-plus', () => ({
  ElMessage: messageMock,
  ElMessageBox: { confirm: confirmMock },
}))

const ENDPOINTS = [
  {
    id: 'self-source',
    name: '本地 Source 端（自己）',
    role: 'SOURCE',
    baseUrl: null,
    instanceId: '00000000-0000-0000-0000-000000000001',
    isSelf: true,
    status: 'READY',
    lastProbeAt: null,
    createdAt: '2026-08-02T00:00:00Z',
    updatedAt: '2026-08-02T00:00:00Z',
  },
  {
    id: 'self-sink',
    name: '本地 Sink 端',
    role: 'SINK',
    baseUrl: 'http://127.0.0.1:19090',
    instanceId: '00000000-0000-0000-0000-000000000001',
    isSelf: true,
    status: 'READY',
    lastProbeAt: null,
    createdAt: '2026-08-02T00:00:00Z',
    updatedAt: '2026-08-02T00:00:00Z',
  },
  {
    id: 'sink-remote',
    name: '生产 Sink-01',
    role: 'SINK',
    baseUrl: 'http://10.0.0.8:19090',
    instanceId: null,
    isSelf: false,
    status: 'UNKNOWN',
    lastProbeAt: null,
    createdAt: '2026-08-02T00:00:00Z',
    updatedAt: '2026-08-02T00:00:00Z',
  },
]

describe('EndpointView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockResolvedValue({ data: ENDPOINTS })
    httpMock.post.mockResolvedValue({
      data: { message: '探活成功', endpoint: { ...ENDPOINTS[2], status: 'READY' } },
    })
  })

  it('展示自身 Source 端与可管理的 Sink 端', async () => {
    const wrapper = mount(EndpointView)
    await flushPromises()

    expect(wrapper.text()).toContain('本地 Source 端（自己）')
    expect(wrapper.text()).toContain('生产 Sink-01')
    expect(wrapper.findAll('[data-test="sink-endpoints"] .endpoint-card')).toHaveLength(2)
  })

  it('探活远程 Sink 端并刷新列表', async () => {
    const wrapper = mount(EndpointView)
    await flushPromises()

    const sinkButtons = wrapper.findAll('[data-test="sink-endpoints"] .endpoint-card__actions button')
    await sinkButtons[0].trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/endpoints/self-sink/probe')
    expect(messageMock.success).toHaveBeenCalledWith('探活成功')
  })

  it('生成本机 Sink 令牌后展示并可复制', async () => {
    const clipboard = { writeText: vi.fn().mockResolvedValue(undefined) }
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: clipboard,
    })
    const wrapper = mount(EndpointView)
    await flushPromises()

    const editButtons = wrapper.findAll('[data-test="sink-endpoints"] .endpoint-card__actions button')
    await editButtons[1].trigger('click')
    await flushPromises()
    const generate = wrapper
      .find('[data-test="endpoint-form"]')
      .findAll('button')
      .find((button) => button.text() === '生成本机新令牌')
    await generate?.trigger('click')
    await flushPromises()

    expect(sinkApiMock.rotateSinkToken).toHaveBeenCalled()
    expect(wrapper.find('[data-test="generated-sink-token"]').text()).toContain('mic_newtoken1234')

    await wrapper.find('[data-test="copy-generated-sink-token"]').trigger('click')
    await flushPromises()
    expect(clipboard.writeText).toHaveBeenCalledWith('mic_newtoken1234')
  })
})
