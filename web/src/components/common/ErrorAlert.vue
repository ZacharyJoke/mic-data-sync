<script setup lang="ts">
/** 稳定错误提示：错误码 + 中文说明 + requestId + 下一步操作（不展示堆栈/敏感值）。 */
defineProps<{
  code?: string | null
  message?: string | null
  requestId?: string | null
  action?: string | null
}>()
</script>

<template>
  <div class="error-alert" data-test="error-alert">
    <p class="error-alert__title">
      <strong>{{ code ?? 'ERROR' }}</strong>
      <span v-if="message">{{ message }}</span>
    </p>
    <p v-if="action" class="error-alert__action">下一步：{{ action }}</p>
    <p v-if="requestId" class="error-alert__meta">requestId：{{ requestId }}</p>
  </div>
</template>

<style scoped>
.error-alert {
  padding: 12px 16px;
  border: 1px solid #fbc4c4;
  border-radius: 6px;
  background: #fef0f0;
  font-size: 13px;
}

.error-alert__title {
  display: flex;
  gap: 8px;
  margin: 0;
  color: #f56c6c;
}

.error-alert__action {
  margin: 6px 0 0;
  color: #e6a23c;
}

.error-alert__meta {
  margin: 4px 0 0;
  color: #909399;
  font-size: 12px;
  word-break: break-all;
}
</style>
