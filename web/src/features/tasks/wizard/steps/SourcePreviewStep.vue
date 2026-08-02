<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

import { sampleRows } from '@/api/sourceMetadata'
import { useTaskWizardStore } from '@/stores/taskWizard'

const store = useTaskWizardStore()
const loadingSample = ref(false)

async function loadSample() {
  const definition = store.draft.readDefinition as Record<string, unknown> | null
  if (store.draft.readMode !== 'TABLE' || !definition?.schema || !definition?.table) {
    ElMessage.warning('请先完成 Table 读取配置')
    return
  }
  loadingSample.value = true
  try {
    const columns = (definition.selectedColumns as string[]) ?? []
    const sample = await sampleRows(
      definition.schema as string,
      definition.table as string,
      columns,
      store.draft.sourceDataSourceId,
    )
    store.patch({
      sourcePreview: sample.rows.map((row) =>
        Object.fromEntries(sample.columns.map((column, columnIndex) => [column, row[columnIndex]])),
      ),
    })
  } catch {
    ElMessage.error('样例查询失败')
  } finally {
    loadingSample.value = false
  }
}
</script>

<template>
  <div class="source-preview-step" data-test="source-preview-step">
    <div class="source-preview-step__head">
      <div>
        <h3>源字段（{{ store.draft.sourceColumns.length }}）</h3>
        <p v-if="store.draft.sourceColumns.length === 0" class="source-preview-step__hint">
          请先在第一步完成读取配置
        </p>
        <div v-else class="source-preview-step__chips">
          <span v-for="column in store.draft.sourceColumns" :key="column" class="source-preview-step__chip">
            {{ column }}
          </span>
        </div>
      </div>
      <el-button
        v-if="store.draft.readMode === 'TABLE'"
        type="primary"
        :loading="loadingSample"
        data-test="load-sample"
        @click="loadSample"
      >
        获取样例
      </el-button>
    </div>

    <div v-if="store.draft.sourcePreview.length > 0" class="source-preview-step__sample">
      <h3>样例数据（最多 20 行）</h3>
      <table data-test="preview-table">
        <thead>
          <tr>
            <th v-for="column in Object.keys(store.draft.sourcePreview[0])" :key="column">
              {{ column }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, rowIndex) in store.draft.sourcePreview" :key="rowIndex">
            <td v-for="(value, column) in row" :key="column">{{ String(value) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.source-preview-step__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.source-preview-step__head h3,
.source-preview-step__sample h3 {
  margin: 0 0 10px;
  color: var(--mic-text);
  font-size: 15px;
}

.source-preview-step__hint {
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.source-preview-step__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.source-preview-step__chip {
  padding: 3px 9px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text);
  background: var(--mic-primary-soft);
  font-size: 12px;
}

.source-preview-step__sample {
  margin-top: 18px;
}

.source-preview-step__sample table {
  border-collapse: collapse;
  font-size: 12px;
}

.source-preview-step__sample th,
.source-preview-step__sample td {
  max-width: 220px;
  overflow: hidden;
  border: 1px solid var(--mic-border);
  padding: 4px 10px;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
