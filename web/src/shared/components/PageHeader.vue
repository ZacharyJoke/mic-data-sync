<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <header class="page-header">
    <div class="page-header__copy">
      <h1 class="page-header__title">
        <slot name="title">{{ title }}</slot>
      </h1>
      <p v-if="description || $slots.description" class="page-header__description">
        <slot name="description">{{ description }}</slot>
      </p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  min-height: 48px;
  margin-bottom: 16px;
}

.page-header__copy {
  min-width: 0;
}

.page-header__title {
  margin: 0;
  color: var(--mic-text);
  font-size: 22px;
  font-weight: 650;
  line-height: 1.35;
  letter-spacing: 0;
}

.page-header__description {
  max-width: 760px;
  margin: 4px 0 0;
  color: var(--mic-text-secondary);
  font-size: 14px;
  line-height: 1.5;
}

.page-header__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

@media (max-width: 767px) {
  .page-header {
    flex-direction: column;
    gap: 12px;
  }

  .page-header__title {
    font-size: 20px;
  }

  .page-header__actions {
    width: 100%;
  }
}
</style>
