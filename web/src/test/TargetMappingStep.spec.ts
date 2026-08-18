import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import TargetMappingStep from '@/features/tasks/wizard/steps/TargetMappingStep.vue'
import { useTaskWizardStore } from '@/stores/taskWizard'

const apiMock = vi.hoisted(() => ({
  getTargetMetadata: vi.fn(),
  listTargetSchemas: vi.fn(),
  listTargetTables: vi.fn(),
}))

vi.mock('@/api/tasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/tasks')>()
  return { ...actual, ...apiMock }
})
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
}))

function mountStep(initial: { targetSchema?: string; targetTable?: string } = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useTaskWizardStore()
  store.patch({
    targetDataSourceId: 'sink-source-1',
    targetSchema: initial.targetSchema ?? '',
    targetTable: initial.targetTable ?? '',
    sourceColumns: ['id', 'order_no'],
  })
  const wrapper = mount(TargetMappingStep, {
    global: { plugins: [pinia], stubs: { 'el-button': true } },
  })
  return { wrapper, store }
}

describe('TargetMappingStep', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMock.listTargetSchemas.mockResolvedValue(['mic_sync', 'public'])
    apiMock.listTargetTables.mockResolvedValue(['patient', 'sales_order'])
    apiMock.getTargetMetadata.mockResolvedValue({
      schema: 'mic_sync',
      table: 'sales_order',
      columns: [
        { name: 'id', typeName: 'int8', nullable: false, primaryKey: true },
        { name: 'order_no', typeName: 'varchar', nullable: false, primaryKey: false },
      ],
      primaryKeyColumns: ['id'],
      uniqueIndexes: [['order_no']],
    })
  })

  it('目标 Schema 和表以级联下拉加载并自动读取目标字段', async () => {
    const { wrapper } = mountStep()
    await flushPromises()

    expect(apiMock.listTargetSchemas).toHaveBeenCalledWith('sink-source-1')
    const schemaOptions = wrapper
      .find('[data-test="target-schema-select"]')
      .findAll('option')
      .map((option) => option.text())
    expect(schemaOptions).toEqual(expect.arrayContaining(['mic_sync', 'public']))

    await wrapper.find('[data-test="target-schema-select"]').setValue('mic_sync')
    await flushPromises()

    expect(apiMock.listTargetTables).toHaveBeenCalledWith('mic_sync', 'sink-source-1')
    const tableOptions = wrapper
      .find('[data-test="target-table-select"]')
      .findAll('option')
      .map((option) => option.text())
    expect(tableOptions).toEqual(expect.arrayContaining(['patient', 'sales_order']))

    await wrapper.find('[data-test="target-table-select"]').setValue('sales_order')
    await flushPromises()

    expect(apiMock.getTargetMetadata).toHaveBeenCalledWith(
      'mic_sync',
      'sales_order',
      'sink-source-1',
    )
    expect(wrapper.text()).toContain('order_no')
  })

  it('切换 Schema 时清空已选目标表并重新加载表列表', async () => {
    const { wrapper, store } = mountStep()
    await flushPromises()

    await wrapper.find('[data-test="target-schema-select"]').setValue('mic_sync')
    await flushPromises()
    await wrapper.find('[data-test="target-table-select"]').setValue('sales_order')
    await flushPromises()
    expect(apiMock.getTargetMetadata).toHaveBeenCalledTimes(1)

    await wrapper.find('[data-test="target-schema-select"]').setValue('public')
    await flushPromises()

    expect(apiMock.listTargetTables).toHaveBeenLastCalledWith('public', 'sink-source-1')
    expect(store.draft.targetTable).toBe('')
    expect(apiMock.getTargetMetadata).toHaveBeenCalledTimes(1)
  })

  it('编辑已有任务时保留目标 Schema/表并加载对应表列表', async () => {
    const { wrapper } = mountStep({ targetSchema: 'mic_sync', targetTable: 'patient' })
    await flushPromises()

    expect(apiMock.listTargetSchemas).toHaveBeenCalledWith('sink-source-1')
    expect(apiMock.listTargetTables).toHaveBeenCalledWith('mic_sync', 'sink-source-1')
    expect(
      (wrapper.find('[data-test="target-schema-select"]').element as HTMLSelectElement).value,
    ).toBe('mic_sync')
    expect(
      (wrapper.find('[data-test="target-table-select"]').element as HTMLSelectElement).value,
    ).toBe('patient')
  })
})
