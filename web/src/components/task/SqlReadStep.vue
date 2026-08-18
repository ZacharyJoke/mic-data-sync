<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { inspectSql, type ResultColumnInfo, type SqlInspectionResult } from '@/api/sourceMetadata'
import SqlInspectionResultView from '@/components/task/SqlInspectionResult.vue'

const emit = defineEmits<{ (event: 'update', value: SqlReadDefinition): void }>()
const props = withDefaults(defineProps<{ initial?: SqlReadDefinition | null; dataSourceId?: string }>(), {
  initial: null,
  dataSourceId: '',
})

export interface SqlReadDefinition {
  mode: 'SQL'
  rawSql: string
  baseTable: string | null
  resultColumns: string[]
  paginationKeys: string[]
  updatedTimeField: string
  incrementalStrategy: 'TIME_WINDOW' | 'DUAL_PHASE'
  incrementalLookbackMinutes: number
}

const sql = ref('')
const inspecting = ref(false)
const result = ref<SqlInspectionResult | null>(null)

const form = reactive<SqlReadDefinition>({
  mode: 'SQL',
  rawSql: '',
  baseTable: null,
  resultColumns: [],
  paginationKeys: [],
  updatedTimeField: '',
  incrementalStrategy: 'TIME_WINDOW',
  incrementalLookbackMinutes: 10,
})

onMounted(() => {
  if (!props.initial) {
    return
  }
  sql.value = props.initial.rawSql
  form.rawSql = props.initial.rawSql
  form.baseTable = props.initial.baseTable
  form.resultColumns = [...(props.initial.resultColumns ?? [])]
  form.paginationKeys = [...(props.initial.paginationKeys ?? [])]
  form.updatedTimeField = props.initial.updatedTimeField ?? ''
  form.incrementalStrategy = props.initial.incrementalStrategy ?? 'TIME_WINDOW'
  form.incrementalLookbackMinutes = props.initial.incrementalLookbackMinutes ?? 10
})

const showPlanFields = computed(
  () =>
    Boolean(result.value?.valid && !(result.value?.duplicateNames?.length)) ||
    Boolean(props.initial),
)

async function handleInspect() {
  if (!sql.value.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  inspecting.value = true
  result.value = null
  try {
    result.value = await inspectSql(sql.value, props.dataSourceId)
    if (result.value.valid && result.value.resultColumns) {
      form.rawSql = sql.value.trim()
      form.baseTable = result.value.tableConversion?.success
        ? (result.value.tableConversion.schema ? result.value.tableConversion.schema + '.' : '') + (result.value.tableConversion.table ?? '')
        : null
      form.resultColumns = result.value.resultColumns.map((column) => column.name)
      form.paginationKeys = result.value.tableConversion?.success && result.value.tableConversion.paginationKeys
        ? [...result.value.tableConversion.paginationKeys]
        : []
    }
  } catch {
    ElMessage.error('SQL 探查请求失败')
  } finally {
    inspecting.value = false
  }
}

function columnOptions(): ResultColumnInfo[] {
  if (result.value?.resultColumns?.length) {
    return result.value.resultColumns
  }
  return (props.initial?.resultColumns ?? []).map((name) => ({
    name,
    typeName: '',
    logicalType: '',
    nullable: true,
  }))
}

watch(
  () => form,
  () => emit('update', { ...form, resultColumns: [...form.resultColumns], paginationKeys: [...form.paginationKeys] }),
  { deep: true },
)
</script>

<template>
  <div class="sql-read-step" data-test="sql-read-step">
    <label class="sql-read-step__field">
      单表只读 SQL（不支持 JOIN / UNION / LIMIT / DML / 副作用函数）
      <textarea
        v-model="sql"
        rows="5"
        placeholder="SELECT id, name FROM patient WHERE status = 'ACTIVE'"
        data-test="sql-input"
      />
    </label>
    <button type="button" :disabled="inspecting" data-test="inspect-button" @click="handleInspect">
      {{ inspecting ? '探查中…' : '校验并探查字段' }}
    </button>

    <SqlInspectionResultView :result="result" />

    <template v-if="showPlanFields">
      <div class="sql-read-step__grid">
        <label class="sql-read-step__field">
          分页 Key（必须为主键或唯一索引）
          <select v-model="form.paginationKeys" multiple>
            <option v-for="column in columnOptions()" :key="column.name" :value="column.name">{{ column.name }}</option>
          </select>
        </label>
        <label class="sql-read-step__field">
          更新时间字段（增量，可选）
          <select v-model="form.updatedTimeField">
            <option value="">不使用</option>
            <option v-for="column in columnOptions()" :key="column.name" :value="column.name">{{ column.name }}</option>
          </select>
        </label>
        <label class="sql-read-step__field" data-test="incremental-strategy">
          增量策略
          <select v-model="form.incrementalStrategy">
            <option value="TIME_WINDOW">时间窗口（默认）</option>
            <option value="DUAL_PHASE">双阶段：主键推进新增 + 时间窗口补更新</option>
          </select>
        </label>
        <label class="sql-read-step__field" data-test="incremental-lookback">
          增量回看窗口（分钟）
          <input v-model.number="form.incrementalLookbackMinutes" type="number" min="1" />
        </label>
      </div>
    </template>
  </div>
</template>

<style scoped>
.sql-read-step__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: #606266;
}

.sql-read-step__field textarea {
  padding: 8px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-family: monospace;
  font-size: 13px;
  resize: vertical;
}

.sql-read-step button {
  margin-top: 8px;
  height: 36px;
  padding: 0 20px;
  border: none;
  border-radius: 4px;
  background: #409eff;
  color: #fff;
  cursor: pointer;
}

.sql-read-step button:disabled {
  background: #a0cfff;
  cursor: not-allowed;
}

.sql-read-step__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-top: 16px;
}

.sql-read-step__grid select {
  height: 36px;
  padding: 0 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}

.sql-read-step__grid select[multiple] {
  height: 96px;
}
</style>
