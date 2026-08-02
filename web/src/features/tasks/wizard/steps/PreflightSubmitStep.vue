<script setup lang="ts">
import { computed } from 'vue'

import { useTaskWizardStore } from '@/stores/taskWizard'

const emit = defineEmits<{
  (event: 'save'): void
  (event: 'validate'): void
}>()

const props = withDefaults(
  defineProps<{
    allowEnable?: boolean
    saveLabel?: string
  }>(),
  {
    allowEnable: true,
    saveLabel: '保存草稿',
  },
)

const store = useTaskWizardStore()

const summary = computed(() => [
  { label: '任务名称', value: store.draft.name || '-' },
  { label: '读取模式', value: store.draft.readMode },
  { label: '目标表', value: store.draft.targetTable || '-' },
  { label: '字段映射', value: `${store.draft.fieldMappings.length} 项` },
  { label: '写入模式', value: store.draft.writeMode },
  { label: 'Sink URL', value: store.draft.remoteSinkUrl || '-' },
])
</script>

<template>
  <div class="preflight-submit-step" data-test="preflight-submit-step">
    <div class="preflight-submit-step__summary">
      <h3>任务摘要</h3>
      <dl>
        <div v-for="item in summary" :key="item.label" class="preflight-submit-step__row">
          <dt>{{ item.label }}</dt>
          <dd>{{ item.value }}</dd>
        </div>
      </dl>
    </div>

    <div class="preflight-submit-step__actions">
      <el-button data-test="save-draft" @click="emit('save')">{{ props.saveLabel }}</el-button>
      <el-button
        v-if="props.allowEnable"
        type="primary"
        data-test="validate-enable"
        @click="emit('validate')"
      >
        校验并启用
      </el-button>
    </div>

  </div>
</template>

<style scoped>
.preflight-submit-step__summary {
  padding: 14px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.preflight-submit-step__summary h3 {
  margin: 0 0 10px;
  color: var(--mic-text);
  font-size: 15px;
}

.preflight-submit-step__row {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr);
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px solid var(--mic-border);
  font-size: 13px;
}

.preflight-submit-step__row:last-child {
  border-bottom: none;
}

.preflight-submit-step__row dt {
  color: var(--mic-text-secondary);
}

.preflight-submit-step__row dd {
  margin: 0;
  color: var(--mic-text);
  word-break: break-all;
}

.preflight-submit-step__actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}
</style>
