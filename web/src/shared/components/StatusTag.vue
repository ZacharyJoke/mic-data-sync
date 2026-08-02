<script setup lang="ts">
import {
  CircleCheckFilled,
  CircleClose,
  CircleCloseFilled,
  Clock,
  Delete,
  EditPen,
  InfoFilled,
  Loading,
  RemoveFilled,
  VideoPause,
  WarningFilled,
} from '@element-plus/icons-vue'
import { computed, type Component } from 'vue'

import {
  getRunStatusMeta,
  getTaskStatusMeta,
  type StatusIconName,
} from '@/shared/domain/status'

const statusIcons: Record<StatusIconName, Component> = {
  CircleCheckFilled,
  CircleClose,
  CircleCloseFilled,
  Clock,
  Delete,
  EditPen,
  InfoFilled,
  Loading,
  RemoveFilled,
  VideoPause,
  WarningFilled,
}

const props = withDefaults(
  defineProps<{
    status: string
    kind?: 'run' | 'task'
  }>(),
  {
    kind: 'run',
  },
)

const meta = computed(() =>
  props.kind === 'task' ? getTaskStatusMeta(props.status) : getRunStatusMeta(props.status),
)

const iconComponent = computed(() => statusIcons[meta.value.icon])
</script>

<template>
  <span
    class="status-tag"
    :class="`status-tag--${meta.tone}`"
    :aria-label="`状态：${meta.label}`"
  >
    <component :is="iconComponent" class="status-tag__icon" aria-hidden="true" />
    <span>{{ meta.label }}</span>
  </span>
</template>

<style scoped>
.status-tag {
  display: inline-flex;
  min-height: 24px;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border: 1px solid currentcolor;
  border-radius: var(--mic-radius);
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  white-space: nowrap;
}

.status-tag__icon {
  width: 14px;
  height: 14px;
  flex: 0 0 14px;
}

.status-tag--primary {
  color: var(--mic-primary);
  background: var(--mic-primary-soft);
}

.status-tag--success {
  color: var(--mic-success);
  background: var(--mic-success-soft);
}

.status-tag--warning {
  color: var(--mic-warning-text);
  background: var(--mic-warning-soft);
}

.status-tag--danger {
  color: var(--mic-danger);
  background: var(--mic-danger-soft);
}

.status-tag--neutral {
  color: var(--mic-text-secondary);
  background: var(--mic-neutral-soft);
}
</style>
