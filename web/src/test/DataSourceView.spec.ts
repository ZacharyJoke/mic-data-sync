import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import DataSourceView from '@/views/DataSourceView.vue'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
const messageMock = vi.hoisted(() => ({ warning: vi.fn(), success: vi.fn(), error: vi.fn() }))
const confirmMock = vi.hoisted(() => vi.fn().mockResolvedValue(true))

vi.mock('@/api/http', () => ({ default: httpMock }))
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
    id: 'sink-remote',
    name: '生产 Sink-01',
    role: 'SINK',
    baseUrl: 'http://10.0.0.8:19090/mic-data-sync',
    instanceId: null,
    isSelf: false,
    status: 'READY',
    lastProbeAt: null,
    createdAt: '2026-08-02T00:00:00Z',
    updatedAt: '2026-08-02T00:00:00Z',
  },
]

const DATA_SOURCES = [
  {
    id: 'ds-1',
    endpointId: 'self-source',
    name: '生产库 A',
    role: 'SOURCE',
    product: 'OPEN_GAUSS',
    jdbcUrl: 'jdbc:opengauss://db:15432/mic_sync',
    username: 'mic_prod',
    driverType: 'opengauss',
    createdAt: null,
    updatedAt: null,
  },
]

describe('DataSourceView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    httpMock.get.mockImplementation((url: string) => {
      if (url === '/endpoints') {
        return Promise.resolve({ data: ENDPOINTS })
      }
      if (url === '/data-sources') {
        return Promise.resolve({ data: DATA_SOURCES })
      }
      return Promise.resolve({ data: [] })
    })
    httpMock.post.mockResolvedValue({
      data: {
        ok: true,
        productName: 'openGauss',
        productVersion: '5.0',
        selectCapable: true,
        transactionCapable: true,
        currentUser: 'mic_prod',
        errorCode: null,
        message: null,
      },
    })
  })

  it('默认选中本地 Source 端并展示数据源，测试连接下发到所属端', async () => {
    const wrapper = mount(DataSourceView)
    await flushPromises()

    expect((wrapper.find('[data-test="endpoint-select"]').element as HTMLSelectElement).value).toBe(
      'self-source',
    )
    expect(wrapper.text()).toContain('生产库 A')

    await wrapper.find('[data-test="data-source-row"] button').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/data-sources/test', expect.objectContaining({ id: 'ds-1' }))
    expect(wrapper.text()).toContain('连接成功')
  })
})
