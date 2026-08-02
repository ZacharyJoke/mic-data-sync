import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { describe, expect, it } from 'vitest'

import FieldMappingStep from '@/components/task/FieldMappingStep.vue'
import type { FieldMapping } from '@/api/tasks'

const Harness = defineComponent({
  components: { FieldMappingStep },
  setup() {
    const sourceColumns = ref<string[]>(['id', 'name'])
    const targetColumns = ref<string[]>(['id'])
    const mappings = ref<FieldMapping[]>([])
    const updateCount = ref(0)

    function onUpdate(value: FieldMapping[]) {
      updateCount.value += 1
      mappings.value = value
    }

    return { sourceColumns, targetColumns, mappings, updateCount, onUpdate }
  },
  // 模拟 TargetMappingStep：每次渲染都用 map() 生成新的 targetColumns 数组
  template: `
    <FieldMappingStep
      :source-columns="sourceColumns"
      :target-columns="targetColumns.map((column) => column)"
      @update="onUpdate"
    />
  `,
})

describe('FieldMappingStep', () => {
  it('目标字段加载后映射稳定且不触发渲染循环', async () => {
    const wrapper = mount(Harness)
    const vm = wrapper.vm as unknown as {
      targetColumns: string[]
      updateCount: number
      mappings: FieldMapping[]
    }

    vm.targetColumns = ['id', 'name']
    await nextTick()
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 50))

    expect(wrapper.find('[data-test="field-mapping"]').exists()).toBe(true)
    expect(vm.updateCount).toBeLessThanOrEqual(4)
    expect(vm.mappings.map((mapping) => mapping.sourceField)).toContain('name')
  })
})
