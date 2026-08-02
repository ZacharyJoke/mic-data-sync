<script setup lang="ts">
import { computed, onMounted, reactive, watch } from 'vue'

import type { FieldMapping } from '@/api/tasks'

const props = defineProps<{ sourceColumns: string[]; targetColumns: string[] }>()
const emit = defineEmits<{ (event: 'update', value: FieldMapping[]): void }>()

const mappings = reactive<FieldMapping[]>([])
let lastEmittedSignature = ''

// 用内容签名监听字段变化，避免父组件每次渲染生成新数组导致无限循环
const sourceSignature = computed(() => props.sourceColumns.join('\u0000'))
const targetSignature = computed(() => props.targetColumns.join('\u0000'))

onMounted(() => {
  // 同名字段自动建议映射；不同名不强行映射
  const sourceSet = new Set(props.sourceColumns.map((c) => c.toLowerCase()))
  const auto = props.sourceColumns
    .filter((column) => sourceSet.has(column.toLowerCase()))
    .filter((column) => props.targetColumns.some((t) => t.toLowerCase() === column.toLowerCase()))
    .map((column) => ({ sourceField: column, targetField: column }))
  mappings.splice(0, mappings.length, ...auto)
  emitUpdate()
})

function addMapping() {
  mappings.push({ sourceField: '', targetField: '' })
  emitUpdate()
}

function removeMapping(index: number) {
  mappings.splice(index, 1)
  emitUpdate()
}

function emitUpdate() {
  const next = mappings.map((m) => ({ ...m }))
  const signature = JSON.stringify(next)
  if (signature === lastEmittedSignature) {
    return
  }
  lastEmittedSignature = signature
  emit('update', next)
}

watch(
  [sourceSignature, targetSignature],
  () => {
    // 源/目标字段变化时，清理失效映射并补充同名字段建议
    const valid = mappings.filter(
      (m) => props.sourceColumns.includes(m.sourceField) && props.targetColumns.includes(m.targetField),
    )
    const sourceSet = new Set(props.sourceColumns.map((c) => c.toLowerCase()))
    const mapped = new Set(valid.map((m) => m.sourceField.toLowerCase()))
    for (const column of props.sourceColumns) {
      if (!mapped.has(column.toLowerCase()) && sourceSet.has(column.toLowerCase())
        && props.targetColumns.some((t) => t.toLowerCase() === column.toLowerCase())) {
        valid.push({ sourceField: column, targetField: column })
      }
    }
    mappings.splice(0, mappings.length, ...valid)
    emitUpdate()
  },
)

watch(mappings, () => emitUpdate(), { deep: true })
</script>

<template>
  <div class="field-mapping" data-test="field-mapping">
    <p class="field-mapping__hint">同名字段已自动建议映射；名称不同不自动强行映射，请手动选择。</p>
    <div v-for="(mapping, index) in mappings" :key="index" class="field-mapping__row">
      <select v-model="mapping.sourceField">
        <option value="" disabled>源字段</option>
        <option v-for="column in sourceColumns" :key="column" :value="column">{{ column }}</option>
      </select>
      <span class="field-mapping__arrow">→</span>
      <select v-model="mapping.targetField">
        <option value="" disabled>目标字段</option>
        <option v-for="column in targetColumns" :key="column" :value="column">{{ column }}</option>
      </select>
      <button type="button" class="field-mapping__remove" @click="removeMapping(index)">删除</button>
    </div>
    <button type="button" class="field-mapping__add" @click="addMapping">+ 添加映射</button>
  </div>
</template>

<style scoped>
.field-mapping__hint {
  color: #909399;
  font-size: 13px;
}

.field-mapping__row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.field-mapping__row select {
  height: 32px;
  padding: 0 8px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}

.field-mapping__arrow {
  color: #909399;
}

.field-mapping__remove {
  border: none;
  background: transparent;
  color: #f56c6c;
  cursor: pointer;
}

.field-mapping__add {
  margin-top: 8px;
  border: 1px dashed #dcdfe6;
  background: transparent;
  border-radius: 4px;
  padding: 6px 16px;
  cursor: pointer;
}
</style>
