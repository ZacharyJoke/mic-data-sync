import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { navigationItems } from '@/app/navigation'
import StatusTag from '@/shared/components/StatusTag.vue'
import { getRunStatusMeta, getTaskStatusMeta } from '@/shared/domain/status'

describe('应用导航', () => {
  it('固定展示六个一级入口', () => {
    expect(navigationItems.map((item) => item.label)).toEqual([
      '工作台',
      '端管理',
      '数据源',
      '同步任务',
      '运行记录',
      '系统管理',
    ])
  })
})

describe('状态字典', () => {
  it('返回失败运行状态的文本、语义色和图标', () => {
    expect(getRunStatusMeta('FAILED')).toMatchObject({
      label: '失败',
      tone: 'danger',
      icon: 'CircleCloseFilled',
    })
  })

  it('返回已启用任务状态', () => {
    expect(getTaskStatusMeta('ENABLED').label).toBe('已启用')
  })

  it('未知运行状态保留原文本并使用中性样式', () => {
    expect(getRunStatusMeta('CUSTOM_STATE')).toEqual({
      label: 'CUSTOM_STATE',
      tone: 'neutral',
      icon: 'InfoFilled',
    })
  })
})

describe('StatusTag', () => {
  it('同时渲染状态图标和文本', () => {
    const wrapper = mount(StatusTag, {
      props: {
        status: 'FAILED',
      },
    })

    expect(wrapper.find('.status-tag__icon').exists()).toBe(true)
    expect(wrapper.text()).toContain('失败')
  })
})
