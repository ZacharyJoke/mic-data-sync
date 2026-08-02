import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import SinkStatusView from '@/views/SinkStatusView.vue'

vi.mock('@/api/http', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: { warning: vi.fn(), success: vi.fn(), error: vi.fn() } }))

describe('SinkStatusView', () => {
  it('提示 Sink 端总览已移至工作台', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div />' } }],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(SinkStatusView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Sink 端总览已移至工作台')
    expect(wrapper.find('[data-test="goto-dashboard"]').exists()).toBe(true)
  })
})
