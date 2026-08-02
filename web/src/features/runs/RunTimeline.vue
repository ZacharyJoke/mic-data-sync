<script setup lang="ts">
import { computed } from 'vue'

import type { RunDiagnosis, RunItem } from '@/api/runs'

const props = defineProps<{
  run: RunItem | null
  diagnosis: RunDiagnosis | null
}>()

const STAGES = [
  { key: 'PREFLIGHT', label: '预检' },
  { key: 'SOURCE_READ', label: '读取' },
  { key: 'TRANSPORT', label: '传输' },
  { key: 'TARGET_WRITE', label: '写入' },
  { key: 'CONFIRMATION', label: '确认' },
] as const

const failedIndex = computed(() =>
  props.diagnosis
    ? STAGES.findIndex((stage) => stage.key === props.diagnosis?.stage)
    : -1,
)

const activeIndex = computed(() => {
  if (failedIndex.value >= 0) {
    return failedIndex.value
  }
  const status = props.run?.status
  if (status === 'SUCCEEDED') {
    return STAGES.length - 1
  }
  if (status === 'RUNNING' || status === 'WAITING_RETRY' || status === 'UNKNOWN') {
    return 3
  }
  return 0
})
</script>

<template>
  <ol class="run-timeline" data-test="run-timeline">
    <li
      v-for="(stage, index) in STAGES"
      :key="stage.key"
      class="run-timeline__stage"
      :class="{
        'run-timeline__stage--active': index <= activeIndex,
        'run-timeline__stage--failed': failedIndex >= 0 && index === failedIndex,
      }"
    >
      <span class="run-timeline__dot" aria-hidden="true" />
      <span>{{ stage.label }}</span>
    </li>
  </ol>
</template>

<style scoped>
.run-timeline {
  display: flex;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-x: auto;
}

.run-timeline__stage {
  display: flex;
  min-width: 96px;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  color: var(--mic-text-secondary);
  font-size: 13px;
  white-space: nowrap;
}

.run-timeline__dot {
  width: 10px;
  height: 10px;
  flex: 0 0 10px;
  border: 2px solid var(--mic-border);
  border-radius: 50%;
  background: var(--mic-surface);
}

.run-timeline__stage--active {
  color: var(--mic-primary);
  font-weight: 600;
}

.run-timeline__stage--active .run-timeline__dot {
  border-color: var(--mic-primary);
  background: var(--mic-primary);
}

.run-timeline__stage--failed {
  color: var(--mic-danger);
}

.run-timeline__stage--failed .run-timeline__dot {
  border-color: var(--mic-danger);
  background: var(--mic-danger);
}
</style>
