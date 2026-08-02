import { defineStore } from 'pinia'
import { ref } from 'vue'

import type { CreateTaskRequest } from '@/api/tasks'

export const TASK_WIZARD_STORAGE_KEY = 'mic-data-sync:task-wizard-draft'

export interface TaskWizardDraft extends CreateTaskRequest {
  currentStep: 1 | 2 | 3 | 4
  sourceColumns: string[]
  sourcePreview: Array<Record<string, unknown>>
  remoteSinkUrl: string
  expectedSinkInstanceId: string
  sourceEndpointId: string
  sinkEndpointId: string
  sourceDataSourceId: string
  targetDataSourceId: string
  targetSchema: string
  lifecycleStatus?: string
}

const emptyDraft = (): TaskWizardDraft => ({
  currentStep: 1,
  name: '',
  readMode: 'TABLE',
  readDefinition: {},
  targetSchema: '',
  targetTable: '',
  writeMode: 'UPSERT',
  uniqueKeys: [],
  fieldMappings: [],
  remoteSinkUrl: '',
  expectedSinkInstanceId: '',
  sourceEndpointId: '',
  sinkEndpointId: '',
  sourceDataSourceId: '',
  targetDataSourceId: '',
  sourceColumns: [],
  sourcePreview: [],
})

function restoreDraft(): TaskWizardDraft {
  const base = emptyDraft()
  try {
    const raw = localStorage.getItem(TASK_WIZARD_STORAGE_KEY)
    if (!raw) {
      return base
    }
    const parsed = JSON.parse(raw) as Partial<TaskWizardDraft>
    const currentStep =
      typeof parsed.currentStep === 'number' && parsed.currentStep >= 1 && parsed.currentStep <= 4
        ? (parsed.currentStep as 1 | 2 | 3 | 4)
        : 1
    return {
      ...base,
      ...parsed,
      currentStep,
      uniqueKeys: Array.isArray(parsed.uniqueKeys) ? parsed.uniqueKeys : [],
      fieldMappings: Array.isArray(parsed.fieldMappings) ? parsed.fieldMappings : [],
      sourceColumns: Array.isArray(parsed.sourceColumns) ? parsed.sourceColumns : [],
      sourcePreview: Array.isArray(parsed.sourcePreview) ? parsed.sourcePreview : [],
    }
  } catch {
    return base
  }
}

export const useTaskWizardStore = defineStore('taskWizard', () => {
  const draft = ref<TaskWizardDraft>(restoreDraft())
  const dirty = ref(false)

  function patch(value: Partial<TaskWizardDraft>) {
    draft.value = { ...draft.value, ...value }
    dirty.value = true
  }

  function persist() {
    const safe = JSON.parse(JSON.stringify(draft.value)) as Record<string, unknown>
    delete safe.password
    delete safe.sinkToken
    localStorage.setItem(TASK_WIZARD_STORAGE_KEY, JSON.stringify(safe))
    dirty.value = false
  }

  function clear() {
    draft.value = emptyDraft()
    dirty.value = false
    localStorage.removeItem(TASK_WIZARD_STORAGE_KEY)
  }

  return { draft, dirty, patch, persist, clear }
})
