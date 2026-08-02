<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import SqlReadStep, { type SqlReadDefinition } from '@/components/task/SqlReadStep.vue'
import TableReadStep, { type TableReadDefinition } from '@/components/task/TableReadStep.vue'
import { listDataSources, type DataSourceItem } from '@/api/database'
import { listEndpoints, probeEndpoint, type EndpointItem } from '@/api/endpoints'
import { useTaskWizardStore } from '@/stores/taskWizard'

const props = withDefaults(defineProps<{ locked?: boolean }>(), {
  locked: false,
})

const store = useTaskWizardStore()
const sourceEndpoints = ref<EndpointItem[]>([])
const sinkEndpoints = ref<EndpointItem[]>([])
const sourceDataSources = ref<DataSourceItem[]>([])
const sinkTargetDataSources = ref<DataSourceItem[]>([])
const loadingSource = ref(false)
const loadingSink = ref(false)
const probingSink = ref(false)

const name = computed({
  get: () => store.draft.name,
  set: (value: string) => store.patch({ name: value }),
})

const readMode = computed({
  get: () => store.draft.readMode,
  set: (value: 'TABLE' | 'SQL') => store.patch({ readMode: value }),
})

const expectedSinkInstanceId = computed({
  get: () => store.draft.expectedSinkInstanceId,
  set: (value: string) => store.patch({ expectedSinkInstanceId: value }),
})

const sourceDataSourceId = computed({
  get: () => store.draft.sourceDataSourceId,
  set: (value: string) => store.patch({ sourceDataSourceId: value }),
})

const sinkEndpointId = computed({
  get: () => store.draft.sinkEndpointId,
  set: (value: string) => store.patch({ sinkEndpointId: value }),
})

const targetDataSourceId = computed({
  get: () => store.draft.targetDataSourceId,
  set: (value: string) => store.patch({ targetDataSourceId: value }),
})

async function loadEndpoints() {
  try {
    const all = await listEndpoints()
    sourceEndpoints.value = all.filter((endpoint) => endpoint.role === 'SOURCE')
    sinkEndpoints.value = all.filter((endpoint) => endpoint.role === 'SINK')
  } catch {
    ElMessage.error('端列表加载失败')
  }
}

async function loadSourceDataSources() {
  loadingSource.value = true
  try {
    const selfSource = sourceEndpoints.value.find((endpoint) => endpoint.isSelf && endpoint.role === 'SOURCE')
    sourceDataSources.value = await listDataSources(selfSource?.id ?? 'self-source')
  } catch {
    sourceDataSources.value = []
    ElMessage.error('Source 数据源加载失败')
  } finally {
    loadingSource.value = false
  }
}

async function loadSinkTargetDataSources() {
  if (!store.draft.sinkEndpointId) {
    sinkTargetDataSources.value = []
    return
  }
  loadingSink.value = true
  try {
    sinkTargetDataSources.value = await listDataSources(store.draft.sinkEndpointId)
  } catch {
    sinkTargetDataSources.value = []
    ElMessage.error('Sink 目标数据源加载失败，请先探活')
  } finally {
    loadingSink.value = false
  }
}

async function handleProbeSink() {
  if (!store.draft.sinkEndpointId) {
    ElMessage.warning('请先选择 Sink 端')
    return
  }
  probingSink.value = true
  try {
    const result = await probeEndpoint(store.draft.sinkEndpointId)
    ElMessage.success(result.message)
    await loadEndpoints()
    await loadSinkTargetDataSources()
  } catch {
    ElMessage.error('探活失败，请检查地址与管理令牌')
  } finally {
    probingSink.value = false
  }
}

function sinkStatusLabel(status: string): string {
  return status === 'READY' ? '就绪' : status === 'NOT_READY' ? '未就绪' : status === 'UNREACHABLE' ? '不可达' : '未知'
}

watch(
  () => store.draft.sinkEndpointId,
  () => {
    store.patch({ targetDataSourceId: '' })
    void loadSinkTargetDataSources()
  },
)

onMounted(async () => {
  await loadEndpoints()
  await loadSourceDataSources()
  if (store.draft.sinkEndpointId) {
    await loadSinkTargetDataSources()
  }
})

function onTableReadUpdate(value: TableReadDefinition) {
  store.patch({
    readDefinition: value as unknown as Record<string, unknown>,
    sourceColumns: value.selectedColumns,
  })
}

function onSqlReadUpdate(value: SqlReadDefinition) {
  store.patch({
    readDefinition: value as unknown as Record<string, unknown>,
    sourceColumns: value.resultColumns,
  })
}

const tableInitial = computed(
  () =>
    store.draft.readMode === 'TABLE'
      ? (store.draft.readDefinition as unknown as TableReadDefinition | null)
      : null,
)

const sqlInitial = computed(
  () =>
    store.draft.readMode === 'SQL'
      ? (store.draft.readDefinition as unknown as SqlReadDefinition | null)
      : null,
)
</script>

<template>
  <div class="basic-read-step" data-test="basic-read-step">
    <div class="basic-read-step__grid">
      <label class="basic-read-step__field">
        任务名称
        <input
          v-model="name"
          type="text"
          data-test="task-name"
          data-field="name"
          placeholder="例如：患者表同步"
        />
      </label>
      <label class="basic-read-step__field">
        读取模式
        <select v-model="readMode" data-test="read-mode" :disabled="props.locked">
          <option value="TABLE">Table 模式</option>
          <option value="SQL">SQL 模式</option>
        </select>
      </label>
    </div>

    <div class="basic-read-step__grid">
      <label class="basic-read-step__field">
        Source 数据源
        <select v-model="sourceDataSourceId" data-test="source-data-source" :disabled="loadingSource">
          <option value="" disabled>请选择</option>
          <option v-for="item in sourceDataSources" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
      </label>
      <label class="basic-read-step__field">
        Sink 端
        <span class="basic-read-step__sink-row">
          <select v-model="sinkEndpointId" data-test="sink-endpoint">
            <option value="" disabled>请选择</option>
            <option
              v-for="endpoint in sinkEndpoints"
              :key="endpoint.id"
              :value="endpoint.id"
              :disabled="endpoint.status !== 'READY'"
            >
              {{ endpoint.name }}（{{ sinkStatusLabel(endpoint.status) }}）
            </option>
          </select>
          <button type="button" :disabled="probingSink || !sinkEndpointId" data-test="probe-sink" @click="handleProbeSink">
            {{ probingSink ? '探活中…' : '获取 Sink 信息' }}
          </button>
        </span>
      </label>
    </div>

    <div class="basic-read-step__grid">
      <label class="basic-read-step__field">
        Sink 目标数据源
        <select v-model="targetDataSourceId" data-test="sink-target-data-source" :disabled="loadingSink">
          <option value="" disabled>请选择（先探活 Sink 端）</option>
          <option v-for="item in sinkTargetDataSources" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
      </label>
      <label class="basic-read-step__field">
        Sink 实例 ID（自动回填）
        <input
          v-model="expectedSinkInstanceId"
          type="text"
          data-test="sink-instance"
          data-field="expectedSinkInstanceId"
          placeholder="探活后自动填充"
        />
      </label>
    </div>

    <fieldset :disabled="props.locked" class="basic-read-step__read">
      <TableReadStep
        v-if="readMode === 'TABLE'"
        :initial="tableInitial"
        :data-source-id="store.draft.sourceDataSourceId"
        @update="onTableReadUpdate"
      />
      <SqlReadStep
        v-else
        :initial="sqlInitial"
        :data-source-id="store.draft.sourceDataSourceId"
        @update="onSqlReadUpdate"
      />
    </fieldset>
  </div>
</template>

<style scoped>
.basic-read-step {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.basic-read-step__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.basic-read-step__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.basic-read-step__field input,
.basic-read-step__field select {
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text);
  background: var(--mic-surface);
}

.basic-read-step__sink-row {
  display: flex;
  gap: 8px;
}

.basic-read-step__sink-row select {
  flex: 1;
  min-width: 0;
}

.basic-read-step__sink-row button {
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text);
  background: var(--mic-surface);
  cursor: pointer;
  white-space: nowrap;
}

.basic-read-step__read {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: none;
}

@media (max-width: 767px) {
  .basic-read-step__grid {
    grid-template-columns: 1fr;
  }
}
</style>
