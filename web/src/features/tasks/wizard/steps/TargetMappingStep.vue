<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import {
  getTargetMetadata,
  listTargetSchemas,
  listTargetTables,
  type FieldMapping,
  type TargetColumn,
} from '@/api/tasks'
import FieldMappingStep from '@/components/task/FieldMappingStep.vue'
import { useTaskWizardStore } from '@/stores/taskWizard'

const props = withDefaults(defineProps<{ locked?: boolean }>(), {
  locked: false,
})

const store = useTaskWizardStore()
const targetColumns = ref<TargetColumn[]>([])
const targetLoading = ref(false)
const targetSchemas = ref<string[]>([])
const targetTables = ref<string[]>([])
const schemasLoading = ref(false)
const tablesLoading = ref(false)
const uniqueKeysInput = ref(store.draft.uniqueKeys.join(','))

const targetSchema = computed({
  get: () => store.draft.targetSchema,
  set: (value: string) => store.patch({ targetSchema: value }),
})

const targetTable = computed({
  get: () => store.draft.targetTable,
  set: (value: string) => store.patch({ targetTable: value }),
})

const writeMode = computed({
  get: () => store.draft.writeMode,
  set: (value: 'UPSERT' | 'UPSERT_NO_OVERWRITE' | 'INSERT_ONLY' | 'REPLACE_ALL') =>
    store.patch({ writeMode: value }),
})

const showUniqueKeys = computed(
  () => store.draft.writeMode === 'UPSERT' || store.draft.writeMode === 'UPSERT_NO_OVERWRITE',
)

const targetColumnNames = computed(() => targetColumns.value.map((column) => column.name))

onMounted(() => {
  if (store.draft.targetDataSourceId) {
    void loadTargetSchemas()
  }
})

watch(
  () => store.draft.targetDataSourceId,
  async (dataSourceId, previous) => {
    if (previous !== undefined && previous !== '' && previous !== dataSourceId) {
      targetSchema.value = ''
      targetTable.value = ''
      targetColumns.value = []
    }
    await loadTargetSchemas()
  },
)

watch(targetSchema, async (schema, previous) => {
  if (previous === undefined) {
    return
  }
  if (previous !== schema) {
    targetTable.value = ''
    targetColumns.value = []
  }
  await loadTargetTables(schema)
})

async function loadTargetSchemas() {
  schemasLoading.value = true
  try {
    targetSchemas.value = await listTargetSchemas(store.draft.targetDataSourceId)
  } catch {
    ElMessage.error('目标 Schema 列表加载失败')
    targetSchemas.value = []
  } finally {
    schemasLoading.value = false
  }
  if (targetSchema.value) {
    await loadTargetTables(targetSchema.value)
  }
}

async function loadTargetTables(schema: string) {
  if (!schema) {
    targetTables.value = []
    return
  }
  tablesLoading.value = true
  try {
    targetTables.value = await listTargetTables(schema, store.draft.targetDataSourceId)
  } catch {
    ElMessage.error('目标表列表加载失败')
    targetTables.value = []
  } finally {
    tablesLoading.value = false
  }
}

function applyUniqueKeys() {
  store.patch({
    uniqueKeys: uniqueKeysInput.value.split(',').map((key) => key.trim()).filter(Boolean),
  })
}

async function loadTargetMetadata() {
  if (!targetTable.value.trim()) {
    ElMessage.warning('请输入目标表名')
    return
  }
  if (!store.draft.targetDataSourceId) {
    ElMessage.warning('请先在第一步选择 Sink 目标数据源')
    return
  }
  targetLoading.value = true
  try {
    const metadata = await getTargetMetadata(
      targetSchema.value || null,
      targetTable.value.trim(),
      store.draft.targetDataSourceId,
    )
    targetColumns.value = metadata.columns
    if (store.draft.uniqueKeys.length === 0 && metadata.primaryKeyColumns.length > 0) {
      store.patch({ uniqueKeys: [...metadata.primaryKeyColumns] })
      uniqueKeysInput.value = metadata.primaryKeyColumns.join(',')
    }
  } catch {
    ElMessage.error('目标表元数据加载失败，请确认目标数据源可访问且表存在')
    targetColumns.value = []
  } finally {
    targetLoading.value = false
  }
}

function onMappingsUpdate(value: FieldMapping[]) {
  store.patch({ fieldMappings: value })
}
</script>

<template>
  <div class="target-mapping-step" data-test="target-mapping-step">
    <p class="target-mapping-step__hint">
      目标数据源：{{ store.draft.targetDataSourceId || '未选择（请先到第一步选择 Sink 端并探活）' }}
    </p>
    <div class="target-mapping-step__grid">
      <label class="target-mapping-step__field">
        目标 Schema
        <select
          v-model="targetSchema"
          data-test="target-schema-select"
          :disabled="props.locked || schemasLoading || !store.draft.targetDataSourceId"
        >
          <option value="" disabled>请选择</option>
          <option v-if="targetSchema && !targetSchemas.includes(targetSchema)" :value="targetSchema">
            {{ targetSchema }}
          </option>
          <option v-for="schema in targetSchemas" :key="schema" :value="schema">{{ schema }}</option>
        </select>
      </label>
      <label class="target-mapping-step__field">
        目标表
        <select
          v-model="targetTable"
          data-test="target-table-select"
          data-field="targetTable"
          :disabled="props.locked || tablesLoading || !targetSchema"
          @change="loadTargetMetadata"
        >
          <option value="" disabled>请选择</option>
          <option v-if="targetTable && !targetTables.includes(targetTable)" :value="targetTable">
            {{ targetTable }}
          </option>
          <option v-for="table in targetTables" :key="table" :value="table">{{ table }}</option>
        </select>
      </label>
    </div>

    <el-button
      type="primary"
      plain
      :loading="targetLoading"
      :disabled="props.locked || !targetTable.trim()"
      data-test="load-target-metadata"
      @click="loadTargetMetadata"
    >
      加载目标字段
    </el-button>

    <div v-if="targetColumns.length > 0" class="target-mapping-step__mapping">
      <fieldset :disabled="props.locked" class="target-mapping-step__fieldset">
        <FieldMappingStep
          :source-columns="store.draft.sourceColumns"
          :target-columns="targetColumnNames"
          @update="onMappingsUpdate"
        />
      </fieldset>
    </div>

    <div class="target-mapping-step__grid">
      <label class="target-mapping-step__field">
        写入模式
        <select v-model="writeMode" data-field="writeMode" :disabled="props.locked">
          <option value="UPSERT">UPSERT（需唯一 Key）</option>
          <option value="UPSERT_NO_OVERWRITE">UPSERT_NO_OVERWRITE（冲突跳过，保留目标）</option>
          <option value="INSERT_ONLY">INSERT_ONLY（追加）</option>
          <option value="REPLACE_ALL">REPLACE_ALL（全量重导，需人工清空目标表）</option>
        </select>
      </label>
      <label v-if="showUniqueKeys" class="target-mapping-step__field">
        唯一 Key（UPSERT / UPSERT_NO_OVERWRITE）
        <input
          v-model="uniqueKeysInput"
          type="text"
          data-field="uniqueKeys"
          placeholder="id 或 id,updated_time"
          :disabled="props.locked"
          @change="applyUniqueKeys"
        />
      </label>
    </div>
    <div v-if="store.draft.writeMode === 'REPLACE_ALL'" class="target-mapping-step__warn" data-test="replace-all-warning">
      工具不会清空目标表：请先线下清空目标表，任务启动时将校验目标表为空（非空拒绝执行）。
      该模式仅支持全量同步，不支持增量。
    </div>
  </div>
</template>

<style scoped>
.target-mapping-step {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.target-mapping-step__hint {
  margin: 0;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.target-mapping-step__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.target-mapping-step__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.target-mapping-step__field input,
.target-mapping-step__field select {
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text);
  background: var(--mic-surface);
}

.target-mapping-step__warn {
  padding: 10px 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
  color: var(--mic-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.target-mapping-step__mapping {
  padding: 14px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.target-mapping-step__fieldset {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: none;
}

@media (max-width: 767px) {
  .target-mapping-step__grid {
    grid-template-columns: 1fr;
  }
}
</style>
