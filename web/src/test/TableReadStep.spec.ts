import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import TableReadStep, { type TableReadDefinition } from '@/components/task/TableReadStep.vue'
import type { TableMetadataInfo } from '@/api/sourceMetadata'

const metadataApiMock = vi.hoisted(() => ({
  listSchemas: vi.fn(),
  listTables: vi.fn(),
  getTableMetadata: vi.fn(),
  sampleRows: vi.fn(),
}))

vi.mock('@/api/sourceMetadata', () => metadataApiMock)
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
}))

const metadata: TableMetadataInfo = {
  schema: 'public',
  table: 'patient',
  columns: [
    { name: 'id', typeName: 'int8', size: 8, nullable: false, primaryKey: true },
    { name: 'name', typeName: 'varchar', size: 128, nullable: true, primaryKey: false },
    { name: 'created_at', typeName: 'timestamptz', size: 8, nullable: true, primaryKey: false },
  ],
  primaryKeyColumns: ['id'],
  uniqueIndexes: [['id']],
  paginationKeySuggestions: [['id']],
}

function lastUpdate(wrapper: VueWrapper): TableReadDefinition {
  const updates = wrapper.emitted('update') as unknown as TableReadDefinition[][]
  return updates.at(-1)![0]
}

async function selectTable(wrapper: VueWrapper) {
  await wrapper.find('[data-test="schema-select"]').setValue('public')
  await flushPromises()
  await wrapper.find('[data-test="table-select"]').setValue('patient')
  await flushPromises()
}

describe('TableReadStep', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    metadataApiMock.listSchemas.mockResolvedValue(['public'])
    metadataApiMock.listTables.mockResolvedValue(['patient'])
    metadataApiMock.getTableMetadata.mockResolvedValue(metadata)
  })

  it('加载表元数据后默认全选所有同步字段', async () => {
    const wrapper = mount(TableReadStep, { props: { dataSourceId: 'source-1' } })
    await flushPromises()
    await selectTable(wrapper)

    expect(wrapper.find('h4').text()).toContain('3/3')
    expect(wrapper.findAll('.table-read-step__chip--active')).toHaveLength(3)
    expect(lastUpdate(wrapper).selectedColumns).toEqual(['id', 'name', 'created_at'])
  })

  it('支持一键全选与取消全选', async () => {
    const wrapper = mount(TableReadStep, { props: { dataSourceId: 'source-1' } })
    await flushPromises()
    await selectTable(wrapper)

    await wrapper.find('[data-test="clear-columns"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.table-read-step__chip--active')).toHaveLength(0)
    expect(lastUpdate(wrapper).selectedColumns).toEqual([])

    await wrapper.find('[data-test="select-all-columns"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.table-read-step__chip--active')).toHaveLength(3)
    expect(lastUpdate(wrapper).selectedColumns).toEqual(['id', 'name', 'created_at'])
  })

  it('可按字段名筛选', async () => {
    const wrapper = mount(TableReadStep, { props: { dataSourceId: 'source-1' } })
    await flushPromises()
    await selectTable(wrapper)

    await wrapper.find('[data-test="column-filter"]').setValue('id')
    await flushPromises()

    const chips = wrapper.findAll('.table-read-step__chip')
    expect(chips).toHaveLength(1)
    expect(chips[0].text()).toContain('id')
  })

  it('编辑已有任务时保留已保存的字段选择', async () => {
    const initial: TableReadDefinition = {
      mode: 'TABLE',
      schema: 'public',
      table: 'patient',
      selectedColumns: ['id'],
      filters: [],
      paginationKeys: ['id'],
      updatedTimeField: 'created_at',
      incrementalStrategy: 'TIME_WINDOW',
      incrementalLookbackMinutes: 10,
    }

    const wrapper = mount(TableReadStep, { props: { dataSourceId: 'source-1', initial } })
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('h4').text()).toContain('1/3')
    expect(wrapper.findAll('.table-read-step__chip--active')).toHaveLength(1)
    expect(wrapper.find('.table-read-step__chip--active').text()).toContain('id')
  })
})
