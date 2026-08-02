<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { getTargetMetadata, type FieldMapping, type TargetColumn } from '@/api/tasks'
import FieldMappingStep from '@/components/task/FieldMappingStep.vue'
import { useTaskWizardStore } from '@/stores/taskWizard'

const props = withDefaults(defineProps<{ locked?: boolean }>(), {
  locked: false,
})

const store = useTaskWizardStore()
const targetColumns = ref<TargetColumn[]>([])
const targetLoading = ref(false)
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
  set: (value: 'UPSERT' | 'INSERT_ONLY') => store.patch({ writeMode: value }),
})

const targetColumnNames = computed(() => targetColumns.value.map((column) => column.name))

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
        <input v-model="targetSchema" type="text" placeholder="public" :disabled="props.locked" />
      </label>
      <label class="target-mapping-step__field">
        目标表
        <input
          v-model="targetTable"
          type="text"
          data-test="target-table"
          data-field="targetTable"
          placeholder="patient_copy"
          :disabled="props.locked"
        />
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
          <option value="INSERT_ONLY">INSERT_ONLY（追加）</option>
        </select>
      </label>
      <label class="target-mapping-step__field">
        唯一 Key（UPSERT）
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
