<script setup lang="ts">
import type { BatchItem } from '@/api/runs'
import { formatDateTime } from '@/shared/utils/format'

defineProps<{ batches: BatchItem[]; loading?: boolean; total?: number }>()

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    PENDING: '待发送',
    PROCESSING: '发送中',
    UNKNOWN: '未知',
    SUCCEEDED: '成功',
    FAILED: '失败',
    SUPERSEDED: '已替代',
  }
  return labels[status] ?? status
}

function formatTime(value: string | null): string {
  return formatDateTime(value)
}
</script>

<template>
  <div class="batch-list" data-test="batch-list">
    <p v-if="loading" class="batch-list__hint">批次加载中…</p>
    <p v-else-if="batches.length === 0" class="batch-list__hint">暂无批次明细</p>

    <template v-else>
      <div class="batch-list__table-wrap">
        <table class="batch-list__table">
          <thead>
            <tr>
              <th>序号</th>
              <th>状态</th>
              <th>行数</th>
              <th>尝试</th>
              <th>哈希</th>
              <th>时间水位</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="batch in batches" :key="batch.batchId">
              <td>#{{ batch.batchSequence }}</td>
              <td>
                <span
                  class="batch-list__status"
                  :class="`batch-list__status--${batch.status.toLowerCase()}`"
                >
                  {{ statusLabel(batch.status) }}
                </span>
              </td>
              <td>{{ batch.rowCount }}</td>
              <td>{{ batch.attemptCount }}</td>
              <td class="batch-list__hash" :title="batch.payloadHash">{{ batch.payloadHash }}</td>
              <td>{{ formatTime(batch.timeWatermark) }}</td>
              <td>{{ formatTime(batch.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div
        v-for="batch in batches"
        :key="batch.batchId"
        class="batch-card"
        :data-test="`batch-card-${batch.batchId}`"
      >
        <div class="batch-card__header">
          <strong>#{{ batch.batchSequence }}</strong>
          <span class="batch-list__status">{{ statusLabel(batch.status) }}</span>
        </div>
        <p class="batch-card__meta">行数 {{ batch.rowCount }} · 尝试 {{ batch.attemptCount }} 次</p>
        <p class="batch-card__meta">时间水位 {{ formatTime(batch.timeWatermark) }}</p>
        <p class="batch-card__meta batch-card__hash">{{ batch.payloadHash }}</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.batch-list__hint {
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.batch-list__table-wrap {
  overflow: hidden;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.batch-list__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.batch-list__table th,
.batch-list__table td {
  border-bottom: 1px solid var(--mic-border);
  padding: 8px 12px;
  text-align: left;
}

.batch-list__table th {
  color: var(--mic-text-secondary);
  background: var(--mic-neutral-soft);
  font-weight: 600;
}

.batch-list__hash {
  max-width: 260px;
  overflow: hidden;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.batch-list__status {
  padding: 2px 8px;
  border-radius: var(--mic-radius);
  color: var(--mic-text-secondary);
  background: var(--mic-neutral-soft);
  font-size: 12px;
}

.batch-list__status--succeeded {
  color: var(--mic-success);
  background: var(--mic-success-soft);
}

.batch-list__status--failed,
.batch-list__status--unknown {
  color: var(--mic-danger);
  background: var(--mic-danger-soft);
}

.batch-list__status--processing {
  color: var(--mic-primary);
  background: var(--mic-primary-soft);
}

.batch-list__cards {
  display: none;
  flex-direction: column;
  gap: 8px;
}

.batch-card {
  padding: 10px 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.batch-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.batch-card__meta {
  margin: 4px 0 0;
  color: var(--mic-text-secondary);
  font-size: 12px;
}

.batch-card__hash {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  word-break: break-all;
}

@media (max-width: 767px) {
  .batch-list__table-wrap {
    display: none;
  }

  .batch-list__cards {
    display: flex;
  }
}
</style>
