import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import {
  TASK_WIZARD_STORAGE_KEY,
  useTaskWizardStore,
} from '@/stores/taskWizard'

describe('taskWizardStore', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('patch 标记脏状态并合并字段', () => {
    const store = useTaskWizardStore()
    expect(store.dirty).toBe(false)

    store.patch({ name: 'patient-sync', currentStep: 3 })

    expect(store.draft.name).toBe('patient-sync')
    expect(store.draft.currentStep).toBe(3)
    expect(store.dirty).toBe(true)
  })

  it('persist 清除密码和令牌后写入 localStorage', () => {
    const store = useTaskWizardStore()
    store.patch({ name: 'patient-sync', remoteSinkUrl: 'http://sink:19090' })
    store.persist()

    const stored = JSON.parse(localStorage.getItem(TASK_WIZARD_STORAGE_KEY) ?? '{}')
    expect(stored.name).toBe('patient-sync')
    expect(stored).not.toHaveProperty('password')
    expect(stored).not.toHaveProperty('sinkToken')
    expect(store.dirty).toBe(false)
  })

  it('restore 从 localStorage 恢复草稿并限制步骤范围', () => {
    localStorage.setItem(
      TASK_WIZARD_STORAGE_KEY,
      JSON.stringify({ name: 'orders', currentStep: 2, uniqueKeys: ['id'] }),
    )
    setActivePinia(createPinia())
    const store = useTaskWizardStore()

    expect(store.draft.name).toBe('orders')
    expect(store.draft.currentStep).toBe(2)
    expect(store.draft.uniqueKeys).toEqual(['id'])
  })

  it('clear 重置草稿并移除持久化', () => {
    const store = useTaskWizardStore()
    store.patch({ name: 'patient-sync' })
    store.persist()

    store.clear()

    expect(store.draft.name).toBe('')
    expect(store.dirty).toBe(false)
    expect(localStorage.getItem(TASK_WIZARD_STORAGE_KEY)).toBeNull()
  })
})
