<script setup lang="ts">
import type { SqlInspectionResult } from '@/api/sourceMetadata'

defineProps<{ result: SqlInspectionResult | null }>()
</script>

<template>
  <div v-if="result" class="sql-inspection" data-test="sql-inspection">
    <template v-if="!result.valid">
      <p class="sql-inspection__error" data-test="inspection-invalid">
        {{ result.message }}
      </p>
      <p class="sql-inspection__hint">当前 SQL 无法启用，可保存为草稿后再调整。</p>
    </template>

    <template v-else>
      <p v-if="result.duplicateNames && result.duplicateNames.length > 0" class="sql-inspection__error" data-test="inspection-duplicates">
        结果存在重复列名，禁止启用：{{ result.duplicateNames.join('、') }}
      </p>
      <p class="sql-inspection__ok">SQL 校验通过，字段探查完成</p>

      <table class="sql-inspection__table" data-test="inspection-columns">
        <thead>
          <tr>
            <th>字段</th>
            <th>数据库类型</th>
            <th>逻辑类型</th>
            <th>可空</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="column in result.resultColumns ?? []" :key="column.name">
            <td>{{ column.name }}</td>
            <td>{{ column.typeName }}</td>
            <td>{{ column.logicalType }}</td>
            <td>{{ column.nullable ? '是' : '否' }}</td>
          </tr>
        </tbody>
      </table>

      <p class="sql-inspection__meta" data-test="inspection-fingerprint">
        结构指纹：<code>{{ result.structureFingerprint }}</code>
      </p>

      <div v-if="result.tableConversion && result.tableConversion.success" class="sql-inspection__convert" data-test="inspection-conversion">
        <p>可转换为 Table 模式：{{ result.tableConversion.schema ? result.tableConversion.schema + '.' : '' }}{{ result.tableConversion.table }}</p>
        <p>字段：{{ result.tableConversion.selectedColumns?.join('、') }}</p>
        <p v-if="result.tableConversion.paginationKeys?.length">分页键建议：{{ result.tableConversion.paginationKeys.join('、') }}</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.sql-inspection__error {
  color: #f56c6c;
  font-weight: 600;
}

.sql-inspection__ok {
  color: #67c23a;
  font-weight: 600;
}

.sql-inspection__hint {
  color: #909399;
  font-size: 13px;
}

.sql-inspection__table {
  margin-top: 8px;
  border-collapse: collapse;
  font-size: 13px;
}

.sql-inspection__table th,
.sql-inspection__table td {
  border: 1px solid #ebeef5;
  padding: 4px 10px;
  text-align: left;
}

.sql-inspection__meta {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}

.sql-inspection__convert {
  margin-top: 8px;
  padding: 8px 12px;
  border: 1px solid #e1f3d8;
  border-radius: 4px;
  background: #f0f9eb;
  font-size: 13px;
}
</style>
