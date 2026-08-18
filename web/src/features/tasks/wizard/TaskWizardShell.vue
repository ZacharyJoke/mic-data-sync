<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import type { AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

import { toApiErrorInfo, type ApiResponse } from '@/api/http'
import {
  createTask,
  enableTask,
  getTask,
  preflightTask,
  updateTask,
  type TaskItem,
  type ValidationIssue,
} from '@/api/tasks'
import { startFullSync } from '@/api/runs'
import PageHeader from '@/shared/components/PageHeader.vue'
import ValidationIssueList from '@/shared/components/ValidationIssueList.vue'
import { useTaskWizardStore } from '@/stores/taskWizard'
import BasicReadStep from '@/features/tasks/wizard/steps/BasicReadStep.vue'
import SourcePreviewStep from '@/features/tasks/wizard/steps/SourcePreviewStep.vue'
import TargetMappingStep from '@/features/tasks/wizard/steps/TargetMappingStep.vue'
import PreflightSubmitStep from '@/features/tasks/wizard/steps/PreflightSubmitStep.vue'

const steps = [
  { value: 1, title: '基本信息与源端读取' },
  { value: 2, title: '源数据确认' },
  { value: 3, title: '目标与字段映射' },
  { value: 4, title: '预检与提交' },
] as const

const route = useRoute()
const router = useRouter()
const editTaskId = typeof route.params.taskId === 'string' ? route.params.taskId : null

const store = useTaskWizardStore()
const submitting = ref(false)
const validationIssues = ref<ValidationIssue[]>([])
const taskId = ref<string | null>(null)
const enabledTaskId = ref<string | null>(null)
const taskLifecycle = ref<string | null>(null)

const currentStep = computed(() => store.draft.currentStep)
const isEdit = computed(() => editTaskId !== null)
// 已启用/已暂停的任务可能正在或即将同步，语义字段锁定；
// 草稿、已禁用、已阻塞任务无活动同步，允许编辑后重新启用。
const locked = computed(
  () => isEdit.value && (taskLifecycle.value === 'ENABLED' || taskLifecycle.value === 'PAUSED'),
)

/** 从 Axios 错误提取后端 message，缺失时使用兜底文案。 */
function errorMessage(error: unknown, fallback: string): string {
  const info = toApiErrorInfo(error as AxiosError<ApiResponse>)
  return info.message || fallback
}

onMounted(async () => {
  if (!editTaskId) {
    return
  }
  try {
    const task = await getTask(editTaskId)
    taskLifecycle.value = task.lifecycleStatus
    taskId.value = task.taskId
    const definition = (task.readDefinition ?? {}) as Record<string, unknown>
    store.patch({
      name: task.name,
      readMode: task.readMode as 'TABLE' | 'SQL',
      readDefinition: definition,
      targetSchema: task.targetSchema ?? '',
      targetTable: task.targetTable,
      writeMode: task.writeMode as 'UPSERT' | 'UPSERT_NO_OVERWRITE' | 'INSERT_ONLY' | 'REPLACE_ALL',
      uniqueKeys: [...task.uniqueKeys],
      fieldMappings: [...task.fieldMappings],
      remoteSinkUrl: task.remoteSinkUrl ?? '',
      expectedSinkInstanceId: task.expectedSinkInstanceId ?? '',
      sourceEndpointId: task.sourceEndpointId ?? '',
      sinkEndpointId: task.sinkEndpointId ?? '',
      sourceDataSourceId: task.sourceDataSourceId ?? '',
      targetDataSourceId: task.targetDataSourceId ?? '',
      sourceColumns:
        task.readMode === 'TABLE'
          ? ((definition.selectedColumns as string[]) ?? [])
          : ((definition.resultColumns as string[]) ?? []),
      lifecycleStatus: task.lifecycleStatus,
    })
  } catch {
    ElMessage.error('任务加载失败')
  }
})

function goToStep(value: number) {
  store.patch({ currentStep: value as 1 | 2 | 3 | 4 })
  validationIssues.value = []
}

function nextStep() {
  if (currentStep.value < 4) {
    goToStep(currentStep.value + 1)
  }
}

function prevStep() {
  if (currentStep.value > 1) {
    goToStep(currentStep.value - 1)
  }
}

function toRequest() {
  const draft = store.draft
  return {
    name: draft.name.trim(),
    readMode: draft.readMode,
    readDefinition: draft.readDefinition,
    targetSchema: draft.targetSchema || undefined,
    targetTable: draft.targetTable.trim(),
    writeMode: draft.writeMode,
    uniqueKeys: draft.uniqueKeys,
    fieldMappings: draft.fieldMappings,
    remoteSinkUrl: draft.remoteSinkUrl.trim() || undefined,
    expectedSinkInstanceId: draft.expectedSinkInstanceId.trim() || undefined,
    sourceEndpointId: draft.sourceEndpointId.trim() || undefined,
    sinkEndpointId: draft.sinkEndpointId.trim() || undefined,
    sourceDataSourceId: draft.sourceDataSourceId.trim() || undefined,
    targetDataSourceId: draft.targetDataSourceId.trim() || undefined,
  }
}

/** 执行保存（不弹错误提示，由调用方决定如何展示失败原因）。 */
async function persistDraft(): Promise<TaskItem> {
  const saved = editTaskId
    ? await updateTask(editTaskId, toRequest())
    : await createTask(toRequest())
  taskId.value = saved.taskId
  store.persist()
  if (isEdit.value) {
    if (locked.value) {
      ElMessage.success('任务已更新')
      await router.push({ name: 'task-detail', params: { taskId: saved.taskId } })
    } else {
      ElMessage.success('修改已保存')
    }
  } else {
    ElMessage.success('草稿已保存')
  }
  return saved
}

async function saveDraft(): Promise<void> {
  submitting.value = true
  try {
    await persistDraft()
  } catch (error) {
    // 保存失败时把后端返回的具体原因展示给用户（如：语义字段不允许直接编辑）
    ElMessage.error(errorMessage(error, '保存失败'))
  } finally {
    submitting.value = false
  }
}

async function validateAndEnable() {
  if (submitting.value) {
    return
  }
  try {
    const report = await preflightTask(toRequest())
    validationIssues.value = report.issues
    if (!report.valid) {
      focusFirstIssue(report.issues[0])
      return
    }
    const saved = await persistDraft()
    await enableTask(saved.taskId)
    store.clear()
    enabledTaskId.value = saved.taskId
    validationIssues.value = []
    ElMessage.success('任务已启用')
  } catch (error) {
    ElMessage.error(errorMessage(error, '预检或启用失败'))
  }
}

async function startFirstFull() {
  if (!enabledTaskId.value) {
    return
  }
  try {
    await startFullSync(enabledTaskId.value)
    ElMessage.success('首次全量已开始')
  } catch {
    ElMessage.error('启动失败（可能并发名额已满或任务未启用）')
  }
}

function focusFirstIssue(issue: ValidationIssue) {
  const stageToStep: Record<string, 1 | 2 | 3 | 4> = {
    SOURCE_CONFIGURATION: 1,
    SOURCE_VALIDATION: 2,
    TARGET_CONFIGURATION: 3,
    TARGET_VALIDATION: 3,
    SINK_HANDSHAKE: 3,
  }
  store.patch({ currentStep: stageToStep[issue.stage] ?? 1 })
  nextTick(() => {
    document.querySelector<HTMLElement>(`[data-field="${issue.field}"]`)?.focus()
  })
}

onBeforeRouteLeave(() => {
  if (!store.dirty) {
    return true
  }
  return window.confirm('当前向导有未保存修改，确认离开？')
})
</script>

<template>
  <section class="task-wizard" data-test="task-wizard">
    <div class="task-wizard__desktop">
      <PageHeader :title="isEdit ? '编辑任务' : '新建任务'" />

      <div v-if="locked" class="task-wizard__lock" data-test="edit-lock-note">
        任务已启用或已暂停，读取定义、目标表、写入模式、唯一键和字段映射不可直接修改；
        可更新名称、Sink URL 与期望实例。
      </div>

      <div class="task-wizard__layout">
        <nav class="task-wizard__steps" data-test="wizard-steps">
          <button
            v-for="step in steps"
            :key="step.value"
            type="button"
            class="task-wizard__step"
            :class="{ 'task-wizard__step--active': currentStep === step.value }"
            :data-test="`wizard-step-${step.value}`"
            @click="goToStep(step.value)"
          >
            <span class="task-wizard__step-index">{{ step.value }}</span>
            <span>{{ step.title }}</span>
          </button>
        </nav>

        <div class="task-wizard__content" data-test="wizard-content">
          <BasicReadStep v-if="currentStep === 1" :locked="locked" />
          <SourcePreviewStep v-else-if="currentStep === 2" />
          <TargetMappingStep v-else-if="currentStep === 3" :locked="locked" />
          <PreflightSubmitStep
            v-else
            :allow-enable="!locked"
            :save-label="locked ? '保存修改' : '保存草稿'"
            @save="saveDraft"
            @validate="validateAndEnable"
          />

          <ValidationIssueList :issues="validationIssues" />

          <div v-if="enabledTaskId" class="task-wizard__enabled" data-test="enabled-success">
            <p>任务已启用，可以启动首次全量。</p>
            <el-button type="primary" data-test="start-first-full" @click="startFirstFull">
              启动首次全量
            </el-button>
          </div>

          <div class="task-wizard__nav-actions">
            <el-button :disabled="currentStep === 1" data-test="wizard-prev" @click="prevStep">
              上一步
            </el-button>
            <el-button
              v-if="currentStep < 4"
              type="primary"
              data-test="wizard-next"
              @click="nextStep"
            >
              下一步
            </el-button>
          </div>
        </div>

        <aside class="task-wizard__summary" data-test="wizard-summary">
          <h3>当前草稿</h3>
          <dl>
            <div><dt>名称</dt><dd :title="store.draft.name || '-'">{{ store.draft.name || '-' }}</dd></div>
            <div><dt>读取模式</dt><dd>{{ store.draft.readMode }}</dd></div>
            <div><dt>目标表</dt><dd :title="store.draft.targetTable || '-'">{{ store.draft.targetTable || '-' }}</dd></div>
            <div><dt>映射数</dt><dd>{{ store.draft.fieldMappings.length }}</dd></div>
            <div><dt>状态</dt><dd>{{ store.dirty ? '未保存' : '已保存' }}</dd></div>
          </dl>
        </aside>
      </div>
    </div>

    <div class="task-wizard-mobile-block" data-test="wizard-mobile-block">
      <h2>{{ isEdit ? '编辑任务' : '新建任务' }}</h2>
      <p>请在平板或桌面完成任务配置</p>
    </div>
  </section>
</template>

<style scoped>
.task-wizard__layout {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) 280px;
  gap: 20px;
  min-height: calc(100vh - 112px);
}

.task-wizard__steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.task-wizard__step {
  display: flex;
  min-height: 44px;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text-secondary);
  background: var(--mic-surface);
  text-align: left;
  cursor: pointer;
}

.task-wizard__step--active {
  border-color: var(--mic-primary);
  color: var(--mic-primary);
  background: var(--mic-primary-soft);
  font-weight: 600;
}

.task-wizard__step-index {
  display: inline-flex;
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #ffffff;
  background: var(--mic-primary);
  font-size: 12px;
}

.task-wizard__content {
  min-width: 0;
}

.task-wizard__lock {
  margin-bottom: 14px;
  padding: 10px 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text-secondary);
  background: var(--mic-neutral-soft);
  font-size: 13px;
}

.task-wizard__nav-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}

.task-wizard__summary {
  align-self: start;
  padding: 14px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.task-wizard__summary h3 {
  margin: 0 0 10px;
  color: var(--mic-text);
  font-size: 15px;
}

.task-wizard__summary dl {
  margin: 0;
}

.task-wizard__summary dl > div {
  display: grid;
  grid-template-columns: 70px minmax(0, 1fr);
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid var(--mic-border);
  font-size: 13px;
}

.task-wizard__summary dl > div:last-child {
  border-bottom: none;
}

.task-wizard__summary dt {
  color: var(--mic-text-secondary);
}

.task-wizard__summary dd {
  margin: 0;
  overflow: hidden;
  color: var(--mic-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-wizard__enabled {
  margin-top: 16px;
  padding: 14px;
  border: 1px solid var(--mic-success);
  border-radius: var(--mic-radius);
  color: var(--mic-success);
  background: var(--mic-success-soft);
}

.task-wizard__enabled p {
  margin: 0 0 10px;
}

.task-wizard-mobile-block {
  display: none;
}

@media (max-width: 1023px) {
  .task-wizard__layout {
    grid-template-columns: 180px minmax(0, 1fr);
  }

  .task-wizard__summary {
    grid-column: 1 / -1;
  }
}

@media (max-width: 767px) {
  .task-wizard__desktop {
    display: none;
  }

  .task-wizard-mobile-block {
    display: block;
  }
}
</style>
