<script setup lang="ts">
import type { RunDiagnosis } from '@/api/runs'

defineProps<{ diagnosis: RunDiagnosis }>()
</script>

<template>
  <section class="run-diagnosis" data-test="run-diagnosis">
    <h3>失败诊断</h3>
    <p class="run-diagnosis__summary">{{ diagnosis.summary }}</p>
    <p class="run-diagnosis__meta">阶段：{{ diagnosis.stage }}</p>
    <p class="run-diagnosis__meta">影响：{{ diagnosis.impact }}</p>
    <p class="run-diagnosis__meta run-diagnosis__mono">requestId：{{ diagnosis.requestId }}</p>
    <ul class="run-diagnosis__actions">
      <li v-for="action in diagnosis.suggestedActions" :key="action.type">
        {{ action.label }}
      </li>
    </ul>
  </section>
</template>

<style scoped>
.run-diagnosis {
  margin-top: 16px;
  padding: 14px 16px;
  border: 1px solid var(--mic-danger);
  border-left: 4px solid var(--mic-danger);
  border-radius: var(--mic-radius);
  background: var(--mic-danger-soft);
}

.run-diagnosis h3 {
  margin: 0 0 8px;
  color: var(--mic-danger);
  font-size: 15px;
}

.run-diagnosis__summary {
  margin: 0;
  color: var(--mic-text);
  font-weight: 600;
}

.run-diagnosis__meta {
  margin: 4px 0 0;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.run-diagnosis__mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.run-diagnosis__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.run-diagnosis__actions li {
  padding: 4px 10px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text-secondary);
  background: var(--mic-surface);
  font-size: 12px;
}
</style>
