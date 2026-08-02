<script setup lang="ts">
import type { RunItem } from '@/api/runs'
import StatusTag from '@/shared/components/StatusTag.vue'
import { formatDateTime } from '@/shared/utils/format'

defineProps<{ run: RunItem }>()

function kindLabel(kind: string): string {
  const labels: Record<string, string> = {
    INITIAL_FULL: '首次全量',
    CATCH_UP: '自动追赶',
    INCREMENTAL: '增量',
    MANUAL: '手动',
  }
  return labels[kind] ?? kind
}
</script>

<template>
  <div class="run-summary" :data-test="`run-card-${run.runId}`">
    <div class="run-summary__header">
      <strong class="run-summary__kind">{{ kindLabel(run.kind) }}</strong>
      <StatusTag :status="run.status" />
    </div>
    <p class="run-summary__meta">
      读取 {{ run.sourceRowCount }} 行 · 确认 {{ run.confirmedRowCount }} 行
    </p>
    <p class="run-summary__meta">
      开始 {{ formatDateTime(run.startedAt) }}
      <template v-if="run.endedAt"> · 结束 {{ formatDateTime(run.endedAt) }}</template>
    </p>
    <p v-if="run.pauseReason" class="run-summary__meta run-summary__meta--warning">
      暂停原因：{{ run.pauseReason }}
    </p>
    <router-link :to="`/runs/${run.runId}`" class="run-summary__link">查看详情 →</router-link>
  </div>
</template>

<style scoped>
.run-summary {
  padding: 12px 14px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.run-summary__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.run-summary__kind {
  color: var(--mic-text);
  font-size: 14px;
}

.run-summary__meta {
  margin: 6px 0 0;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.run-summary__meta--warning {
  color: var(--mic-warning-text);
}

.run-summary__link {
  display: inline-block;
  margin-top: 8px;
  color: var(--mic-primary);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
}
</style>
