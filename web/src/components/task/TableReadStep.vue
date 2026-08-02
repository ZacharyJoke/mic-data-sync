<script setup lang="ts">
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
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

const form = reactive<TableReadDefinition>({
  mode: 'TABLE',
  schema: '',
  table: '',
  selectedColumns: [],
  filters: [],
  paginationKeys: [],
  updatedTimeField: '',
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
    }
    metadata.value = null
    sample.value = null
    if (!schema) {
      tables.value = []
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
    }
    metadata.value = null
    sample.value = null
    if (!schema || !table) {
      return
    }
    loadingMetadata.value = true
    try {
      metadata.value = await getTableMetadata(schema, table, props.dataSourceId)
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

function toggleColumn(name: string) {
  const index = form.selectedColumns.indexOf(name)
  if (index >= 0) {
    form.selectedColumns.splice(index, 1)
  } else {
    form.selectedColumns.push(name)
  }
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
        <h4>同步字段（{{ form.selectedColumns.length }}/{{ columnOptions().length }}）</h4>
        <div class="table-read-step__chips">
          <button
            v-for="column in columnOptions()"
            :key="column.name"
            type="button"
            class="table-read-step__chip"
            :class="{ 'table-read-step__chip--active': form.selectedColumns.includes(column.name) }"
            @click="toggleColumn(column.name)"
          >
            {{ column.name }}<span v-if="column.primaryKey" class="table-read-step__pk">PK</span>
          </button>
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
          分页 Key（必须为主键或唯一索引）
          <select v-model="form.paginationKeys" multiple data-test="pagination-keys">
            <option v-for="column in columnOptions()" :key="column.name" :value="column.name">{{ column.name }}</option>
          </select>
          <small v-if="metadata.paginationKeySuggestions.length === 0" class="table-read-step__warn">
            未发现稳定的主键/唯一索引，任务无法启用
          </small>
        </label>

        <label class="table-read-step__field">
          更新时间字段（增量，可选）
          <select v-model="form.updatedTimeField">
            <option value="">不使用</option>
            <option v-for="column in columnOptions()" :key="column.name" :value="column.name">{{ column.name }}</option>
          </select>
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

.table-read-step__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
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
