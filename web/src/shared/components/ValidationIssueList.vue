<script setup lang="ts">
import type { ValidationIssue } from '@/api/tasks'

defineProps<{ issues: ValidationIssue[] }>()

const stageLabels: Record<string, string> = {
  SOURCE_CONFIGURATION: '源配置',
  SOURCE_VALIDATION: '源校验',
  TARGET_CONFIGURATION: '目标配置',
  TARGET_VALIDATION: '目标校验',
  SINK_HANDSHAKE: 'Sink 握手',
}
</script>

<template>
  <div v-if="issues.length > 0" class="validation-issues" data-test="validation-issues">
    <div
      v-for="issue in issues"
      :key="issue.code + issue.field"
      class="validation-issues__item"
      :class="`validation-issues__item--${issue.severity.toLowerCase()}`"
      :data-field="issue.field"
    >
      <div class="validation-issues__head">
        <strong>{{ issue.severity === 'BLOCKING' ? '阻断' : '警告' }}</strong>
        <span>{{ issue.code }}</span>
      </div>
      <p class="validation-issues__message">{{ issue.message }}</p>
      <p class="validation-issues__meta">
        {{ stageLabels[issue.stage] ?? issue.stage }} · {{ issue.field }}
      </p>
      <p v-if="issue.suggestedAction" class="validation-issues__action">
        建议：{{ issue.suggestedAction }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.validation-issues {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 16px;
}

.validation-issues__item {
  padding: 12px 14px;
  border: 1px solid var(--mic-border);
  border-left: 4px solid var(--mic-warning);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.validation-issues__item--blocking {
  border-left-color: var(--mic-danger);
}

.validation-issues__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.validation-issues__head strong {
  color: var(--mic-text);
}

.validation-issues__head span {
  color: var(--mic-text-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}

.validation-issues__message {
  margin: 6px 0 0;
  color: var(--mic-text);
  font-size: 13px;
}

.validation-issues__meta,
.validation-issues__action {
  margin: 4px 0 0;
  color: var(--mic-text-secondary);
  font-size: 12px;
}
</style>
