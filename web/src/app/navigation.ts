import {
  Connection,
  DataAnalysis,
  Link,
  List,
  Monitor,
  Setting,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'

export interface NavigationItem {
  label: string
  to: string
  icon: Component
  exact?: boolean
}

export const navigationItems: NavigationItem[] = [
  { label: '工作台', to: '/', icon: Monitor, exact: true },
  { label: '端管理', to: '/endpoints', icon: Link },
  { label: '数据源', to: '/data-sources', icon: Connection },
  { label: '同步任务', to: '/tasks', icon: List },
  { label: '运行记录', to: '/runs', icon: DataAnalysis },
  { label: '系统管理', to: '/system', icon: Setting },
]
