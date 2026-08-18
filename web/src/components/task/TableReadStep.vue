<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import {
  getTableMetadata,
  listSchemas,
  listTables,
  sampleRows,
  type ColumnInfo,
  type SampleResult,
  type TableMetadataInfo,
} from '@/api/sourceMetadata'

/** 输出给任务向导的 Table 模式读取定义 */
const emit = defineEmits<{ (event: 'update', value: TableReadDefinition): void }>()
const props = withDefaults(defineProps<{ initial?: TableReadDefinition | null; dataSourceId?: string }>(), {
  initial: null,
  dataSourceId: '',
})

export interface FilterRow {
  column: string
  operator: string
  value: string
}

export interface TableReadDefinition {
  mode: 'TABLE'
  schema: string
  table: string
  selectedColumns: string[]
  filters: FilterRow[]
  paginationKeys: string[]
  updatedTimeField: string
  incrementalStrategy: 'TIME_WINDOW' | 'DUAL_PHASE'
  incrementalLookbackMinutes: number
}

const OPERATORS = ['=', '!=', '>', '>=', '<', '<=', 'IN', 'LIKE']

const schemas = ref<string[]>([])
const tables = ref<string[]>([])
const metadata = ref<TableMetadataInfo | null>(null)
const loadingSchemas = ref(false)
const loadingTables = ref(false)
const loadingMetadata = ref(false)
const testing = ref(false)
const sample = ref<SampleResult | null>(null)
const hydrating = ref(false)
const columnKeyword = ref('')

const form = reactive<TableReadDefinition>({
  mode: 'TABLE',
  schema: '',
  table: '',
  selectedColumns: [],
  filters: [],
  paginationKeys: [],
  updatedTimeField: '',
  incrementalStrategy: 'TIME_WINDOW',
  incrementalLookbackMinutes: 10,
})

onMounted(async () => {
  loadingSchemas.value = true
  try {
    schemas.value = await listSchemas(props.dataSourceId)
  } catch {
    ElMessage.error('Schema 列表加载失败，请检查 Source 数据库配置')
  } finally {
    loadingSchemas.value = false
  }
  if (props.initial) {
    hydrating.value = true
    form.schema = props.initial.schema
    form.table = props.initial.table
    form.selectedColumns = [...(props.initial.selectedColumns ?? [])]
    form.filters = (props.initial.filters ?? []).map((filter) => ({ ...filter }))
    form.paginationKeys = [...(props.initial.paginationKeys ?? [])]
    form.updatedTimeField = props.initial.updatedTimeField ?? ''
    form.incrementalStrategy = props.initial.incrementalStrategy ?? 'TIME_WINDOW'
    form.incrementalLookbackMinutes = props.initial.incrementalLookbackMinutes ?? 10
    await nextTick()
    hydrating.value = false
  }
})

watch(
  () => form.schema,
  async (schema) => {
    const keepInitial = hydrating.value
    if (!keepInitial) {
      form.table = ''
      form.selectedColumns = []
      form.paginationKeys = []
      form.updatedTimeField = ''
      columnKeyword.value = ''
    }
    metadata.value = null
    sample.value = null
    tables.value = []
    if (!schema) {
      return
    }
    loadingTables.value = true
    try {
      tables.value = await listTables(schema, props.dataSourceId)
    } catch {
      ElMessage.error('表列表加载失败')
    } finally {
      loadingTables.value = false
    }
  },
)

watch(
  () => [form.schema, form.table] as const,
  async ([schema, table]) => {
    const keepInitial = hydrating.value
    if (!keepInitial) {
      form.selectedColumns = []
      form.paginationKeys = []
      form.updatedTimeField = ''
      columnKeyword.value = ''
    }
    metadata.value = null
    sample.value = null
    if (!schema || !table) {
      return
    }
    loadingMetadata.value = true
    try {
      metadata.value = await getTableMetadata(schema, table, props.dataSourceId)
      if (!keepInitial || (props.initial?.selectedColumns ?? []).length === 0) {
        form.selectedColumns = metadata.value?.columns.map((column) => column.name) ?? []
      }
      if (keepInitial) {
        return
      }
      // 分页键优先建议主键，其次非空唯一索引
      const suggestions = metadata.value?.paginationKeySuggestions ?? []
      if (suggestions.length > 0) {
        form.paginationKeys = [...suggestions[0]]
      }
    } catch {
      ElMessage.error('表元数据加载失败')
    } finally {
      loadingMetadata.value = false
    }
  },
)

watch(
  () => form,
  () => emit('update', { ...form, selectedColumns: [...form.selectedColumns], paginationKeys: [...form.paginationKeys] }),
  { deep: true },
)

function addFilter() {
  form.filters.push({ column: '', operator: '=', value: '' })
}

function removeFilter(index: number) {
  form.filters.splice(index, 1)
}

async function handleSample() {
  if (!form.schema || !form.table) {
    return
  }
  testing.value = true
  sample.value = null
  try {
    sample.value = await sampleRows(form.schema, form.table, form.selectedColumns, props.dataSourceId)
  } catch {
    ElMessage.error('测试查询失败')
  } finally {
    testing.value = false
  }
}

function columnOptions(): ColumnInfo[] {
  return metadata.value?.columns ?? []
}

const visibleColumnOptions = computed(() => {
  const keyword = columnKeyword.value.trim().toLowerCase()
  if (!keyword) {
    return columnOptions()
  }
  return columnOptions().filter((column) => column.name.toLowerCase().includes(keyword))
})

function toggleColumn(name: string) {
  const index = form.selectedColumns.indexOf(name)
  if (index >= 0) {
    form.selectedColumns.splice(index, 1)
  } else {
    form.selectedColumns.push(name)
  }
}

function selectAllColumns() {
  form.selectedColumns = columnOptions().map((column) => column.name)
}

function clearSelectedColumns() {
  form.selectedColumns = []
}
</script>

<template>
  <div class="table-read-step" data-test="table-read-step">
    <div class="table-read-step__grid">
      <label class="table-read-step__field">
        Schema
        <select v-model="form.schema" data-test="schema-select" :disabled="loadingSchemas">
          <option value="" disabled>请选择</option>
          <option v-for="schema in schemas" :key="schema" :value="schema">{{ schema }}</option>
        </select>
      </label>

      <label class="table-read-step__field">
        表
        <select v-model="form.table" data-test="table-select" :disabled="loadingTables || !form.schema">
          <option value="" disabled>请选择</option>
          <option v-for="table in tables" :key="table" :value="table">{{ table }}</option>
        </select>
      </label>
    </div>

    <template v-if="metadata">
      <div class="table-read-step__section">
        <div class="table-read-step__section-head">
          <h4>同步字段（{{ form.selectedColumns.length }}/{{ columnOptions().length }}）</h4>
          <div class="table-read-step__section-actions">
            <input
              v-model="columnKeyword"
              type="search"
              class="table-read-step__search"
              placeholder="筛选字段"
              data-test="column-filter"
            />
            <button type="button" data-test="select-all-columns" @click="selectAllColumns">全选</button>
            <button type="button" data-test="clear-columns" @click="clearSelectedColumns">取消全选</button>
          </div>
        </div>
        <div class="table-read-step__chips">
          <button
            v-for="column in visibleColumnOptions"
            :key="column.name"
            type="button"
            class="table-read-step__chip"
            :class="{ 'table-read-step__chip--active': form.selectedColumns.includes(column.name) }"
            @click="toggleColumn(column.name)"
          >
            {{ column.name }}<span v-if="column.primaryKey" class="table-read-step__pk">PK</span>
          </button>
          <span v-if="visibleColumnOptions.length === 0" class="table-read-step__empty">没有匹配的字段</span>
        </div>
      </div>

      <div class="table-read-step__section">
        <h4>过滤条件（AND）</h4>
        <div v-for="(filter, index) in form.filters" :key="index" class="table-read-step__filter-row">
          <select v-model="filter.column">
            <option value="" disabled>字段</option>
            <option v-for="column in columnOptions()" :key="column.name" :value="column.name">{{ column.name }}</option>
          </select>
          <select v-model="filter.operator">
            <option v-for="operator in OPERATORS" :key="operator" :value="operator">{{ operator }}</option>
          </select>
          <input v-model="filter.value" type="text" placeholder="值" />
          <button type="button" class="table-read-step__remove" @click="removeFilter(index)">删除</button>
        </div>
        <button type="button" class="table-read-step__add" @click="addFilter">+ 添加条件</button>
      </div>

      <div class="table-read-step__grid">
        <label class="table-read-step__field">
          分页 Key（主键/唯一索引，或启用时实测唯一；REPLACE_ALL 可为任意分批字段）
          <select v-model="form.paginationKeys" multiple data-test="pagination-keys">
            <option v-for="column in columnOptions()" :key="column.name" :value="column.name">{{ column.name }}</option>
          </select>
          <small v-if="metadata.paginationKeySuggestions.length === 0" class="table-read-step__warn">
            未发现主键/唯一索引：分页键组合将在启用时校验实际唯一性；
            若使用 REPLACE_ALL 写入模式，分页键可为任意分批字段或留空
          </small>
        </label>

        <label class="table-read-step__field">
          更新时间字段（增量，可选）
          <select v-model="form.updatedTimeField">
            <option value="">不使用</option>
            <option v-for="column in columnOptions()" :key="column.name" :value="column.name">{{ column.name }}</option>
          </select>
        </label>

        <label class="table-read-step__field" data-test="incremental-strategy">
          增量策略
          <select v-model="form.incrementalStrategy">
            <option value="TIME_WINDOW">时间窗口（默认）</option>
            <option value="DUAL_PHASE">双阶段：主键推进新增 + 时间窗口补更新</option>
          </select>
          <small class="table-read-step__hint">
            源表更新时间与主键顺序不一致时选择“双阶段”，避免增量漏掉“id 新但时间旧”的新行
          </small>
        </label>

        <label class="table-read-step__field" data-test="incremental-lookback">
          增量回看窗口（分钟）
          <input v-model.number="form.incrementalLookbackMinutes" type="number" min="1" />
          <small class="table-read-step__hint">
            双阶段/时间窗口模式下补扫更新的时间范围，建议覆盖日常更新频率（如 1440 = 1 天）
          </small>
        </label>
      </div>

      <div class="table-read-step__section">
        <h4>数据预览（最多 20 行）</h4>
        <button type="button" :disabled="testing" @click="handleSample">
          {{ testing ? '查询中…' : '测试查询' }}
        </button>
        <table v-if="sample" class="table-read-step__sample" data-test="sample-table">
          <thead>
            <tr>
              <th v-for="column in sample.columns" :key="column">{{ column }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in sample.rows" :key="rowIndex">
              <td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.table-read-step__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.table-read-step__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: #606266;
}

.table-read-step__field input,
.table-read-step__field select {
  height: 36px;
  padding: 0 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 14px;
}

.table-read-step__field select[multiple] {
  height: 96px;
}

.table-read-step__section {
  margin-top: 16px;
}

.table-read-step__section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
}

.table-read-step__section-head h4 {
  margin: 0;
}

.table-read-step__section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.table-read-step__section-actions button,
.table-read-step__search {
  height: 28px;
  padding: 0 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 13px;
  background: #fff;
}

.table-read-step__section-actions button {
  color: #409eff;
  cursor: pointer;
}

.table-read-step__search {
  width: 160px;
}

.table-read-step__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.table-read-step__chip {
  padding: 4px 10px;
  border: 1px solid #dcdfe6;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
}

.table-read-step__chip--active {
  background: #409eff;
  color: #fff;
  border-color: #409eff;
}

.table-read-step__pk {
  margin-left: 4px;
  font-size: 11px;
  color: #e6a23c;
}

.table-read-step__chip--active .table-read-step__pk {
  color: #fff;
}

.table-read-step__filter-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.table-read-step__filter-row select,
.table-read-step__filter-row input {
  height: 32px;
  padding: 0 8px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}

.table-read-step__remove {
  border: none;
  background: transparent;
  color: #f56c6c;
  cursor: pointer;
}

.table-read-step__add {
  border: 1px dashed #dcdfe6;
  background: transparent;
  border-radius: 4px;
  padding: 6px 16px;
  cursor: pointer;
}

.table-read-step__warn {
  color: #e6a23c;
}

.table-read-step__empty {
  color: #909399;
  font-size: 13px;
}

.table-read-step__sample {
  margin-top: 8px;
  border-collapse: collapse;
  font-size: 13px;
  max-width: 100%;
  overflow: auto;
  display: block;
}

.table-read-step__sample th,
.table-read-step__sample td {
  border: 1px solid #ebeef5;
  padding: 4px 8px;
  white-space: nowrap;
}
</style>
