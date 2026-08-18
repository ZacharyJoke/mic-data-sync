<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { AxiosError } from 'axios'

import {
  disableTask,
  getTask,
  getTargetMetadata,
  pauseTask,
  resumeTask,
  validateTask,
  type TaskItem,
} from '@/api/tasks'
import { getTableMetadata } from '@/api/sourceMetadata'
import { listEndpoints } from '@/api/endpoints'
import { listTaskRuns, startFullSync, startIncremental, type RunItem } from '@/api/runs'
import { toApiErrorInfo, type ApiResponse } from '@/api/http'
import RunSummaryCard from '@/components/run/RunSummaryCard.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import type { PageResult } from '@/shared/api/page'
import { formatDateTime } from '@/shared/utils/format'

type TaskTab = 'overview' | 'config' | 'history'

const route = useRoute()
const router = useRouter()
const taskId = route.params.taskId as string

const task = ref<TaskItem | null>(null)
const operating = ref(false)
const historyResult = ref<PageResult<RunItem>>({ items: [], total: 0, page: 1, size: 20 })
const historyLoading = ref(false)

const sourceTypes = ref<Record<string, string>>({})
const targetTypes = ref<Record<string, string>>({})
const endpointNames = ref<Record<string, string>>({})

const activeTab = computed<TaskTab>(() => {
  const tab = route.query.tab
  return tab === 'config' || tab === 'history' ? tab : 'overview'
})

async function changeTab(tab: TaskTab) {
  await router.replace({ query: { ...route.query, tab } })
}

onMounted(load)

watch(activeTab, (tab) => {
  if (tab === 'history' && task.value) {
    void loadHistory()
  }
})

async function load() {
  try {
    task.value = await getTask(taskId)
    try {
      const endpoints = await listEndpoints()
      endpointNames.value = Object.fromEntries(endpoints.map((endpoint) => [endpoint.id, endpoint.name]))
    } catch {
      // 端名称解析失败不阻塞详情展示
    }
    await loadFieldTypes()
    if (activeTab.value === 'history') {
      await loadHistory()
    }
  } catch {
    ElMessage.error('任务详情加载失败')
  }
}

async function loadFieldTypes() {
  if (!task.value) {
    return
  }
  try {
    const definition = task.value.readDefinition as Record<string, unknown> | null
    if (task.value.readMode === 'TABLE' && definition?.table) {
      const sourceSchema = (definition.schema as string) || 'public'
      const sourceTable = definition.table as string
      const [sourceMeta, targetMeta] = await Promise.all([
        getTableMetadata(sourceSchema, sourceTable, task.value.sourceDataSourceId ?? undefined),
        getTargetMetadata(task.value.targetSchema, task.value.targetTable, task.value.targetDataSourceId ?? undefined),
      ])
      sourceTypes.value = Object.fromEntries(sourceMeta.columns.map((c) => [c.name, c.typeName]))
      targetTypes.value = Object.fromEntries(targetMeta.columns.map((c) => [c.name, c.typeName]))
    } else {
      const targetMeta = await getTargetMetadata(
        task.value.targetSchema,
        task.value.targetTable,
        task.value.targetDataSourceId ?? undefined,
      )
      targetTypes.value = Object.fromEntries(targetMeta.columns.map((c) => [c.name, c.typeName]))
    }
  } catch {
    // 元数据加载失败不阻塞详情展示
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    historyResult.value = await listTaskRuns(taskId, 1, 20)
  } catch {
    historyResult.value = { items: [], total: 0, page: 1, size: 20 }
  } finally {
    historyLoading.value = false
  }
}

async function runAction(action: 'full' | 'incremental') {
  if (!task.value) {
    return
  }
  const label = action === 'full' ? '首次全量（含自动追赶）' : '手动增量'
  try {
    const replaceAllHint =
      action === 'full' && task.value?.writeMode === 'REPLACE_ALL'
        ? '\n\nREPLACE_ALL 模式不会自动清空目标表，请确认目标表已线下清空。'
        : ''
    await ElMessageBox.confirm(`确认启动「${label}」？${replaceAllHint}`, '确认', { type: 'warning' })
  } catch {
    return
  }
  if (operating.value) {
    return
  }
  operating.value = true
  try {
    if (action === 'full') {
      await startFullSync(taskId)
    } else {
      await startIncremental(taskId)
    }
    ElMessage.success('已开始')
    window.setTimeout(load, 1500)
  } catch (error) {
    const info = toApiErrorInfo(error as AxiosError<ApiResponse>)
    ElMessage.error(info.message || '启动失败（可能并发名额已满或任务未启用）')
  } finally {
    operating.value = false
  }
}

async function handleValidate() {
  try {
    const report = await validateTask(taskId)
    if (report.valid) {
      ElMessage.success('校验通过')
    } else {
      ElMessageBox.alert(
        report.issues.map((issue) => `• ${issue.message}`).join('\n'),
        `校验未通过（${report.issues.length} 项）`,
        { type: 'warning', confirmButtonText: '知道了' },
      )
    }
  } catch {
    ElMessage.error('校验请求失败')
  }
}

async function handlePause() {
  if (!task.value || operating.value) {
    return
  }
  operating.value = true
  try {
    await pauseTask(taskId)
    ElMessage.success('任务已暂停')
    await load()
  } catch {
    ElMessage.error('暂停失败（仅已启用任务可暂停）')
  } finally {
    operating.value = false
  }
}

async function handleResume() {
  if (!task.value || operating.value) {
    return
  }
  operating.value = true
  try {
    await resumeTask(taskId)
    ElMessage.success('任务已继续')
    await load()
  } catch {
    ElMessage.error('继续失败（仅已暂停任务可继续）')
  } finally {
    operating.value = false
  }
}

async function handleDisable() {
  if (!task.value || operating.value) {
    return
  }
  try {
    await ElMessageBox.confirm(`确认禁用任务「${task.value.name}」？禁用后不能触发运行。`, '禁用确认', {
      type: 'warning',
    })
  } catch {
    return
  }
  operating.value = true
  try {
    await disableTask(taskId)
    ElMessage.success('任务已禁用')
    await load()
  } catch {
    ElMessage.error('禁用失败（可能存在活动 Run）')
  } finally {
    operating.value = false
  }
}

function readDefinitionSummary(taskItem: TaskItem): string[] {
  const lines: string[] = []
  const definition = taskItem.readDefinition as Record<string, unknown> | null
  if (!definition) {
    return lines
  }
  if (taskItem.readMode === 'TABLE') {
    lines.push(`源表：${definition.schema ? (definition.schema as string) + '.' : ''}${definition.table}`)
    lines.push(`分页 Key：${((definition.paginationKeys as string[]) ?? []).join('、') || '-'}`)
    lines.push(`更新时间字段：${(definition.updatedTimeField as string) || '未使用'}`)
    const strategy = (definition.incrementalStrategy as string) || 'TIME_WINDOW'
    const lookback = (definition.incrementalLookbackMinutes as number) ?? 10
    lines.push(`增量策略：${strategy === 'DUAL_PHASE' ? '双阶段（主键推进 + 时间窗口）' : '时间窗口'}，回看 ${lookback} 分钟`)
  } else {
    lines.push(`基表：${(definition.baseTable as string) ?? '-'}`)
    lines.push(`SQL：${definition.rawSql}`)
  }
  return lines
}

function writeModeLabel(taskItem: TaskItem): string {
  const labels: Record<string, string> = {
    UPSERT: 'UPSERT（覆盖写入）',
    UPSERT_NO_OVERWRITE: 'UPSERT_NO_OVERWRITE（冲突跳过）',
    INSERT_ONLY: 'INSERT_ONLY（追加）',
    REPLACE_ALL: 'REPLACE_ALL（全量重导）',
  }
  return labels[taskItem.writeMode] ?? taskItem.writeMode
}

function latestRunMeta(): string {
  const latestRun = task.value?.latestRun
  if (!latestRun) {
    return '暂无运行'
  }
  return `${latestRun.kind} · ${formatDateTime(latestRun.startedAt)}`
}
</script>

<template>
  <section v-if="task" class="task-detail" data-test="task-detail">
    <PageHeader :title="task.name" />

    <div class="task-detail__tabs" role="tablist" data-test="task-tabs">
      <button
        type="button"
        class="task-detail__tab"
        :class="{ 'task-detail__tab--active': activeTab === 'overview' }"
        data-test="overview-tab-trigger"
        role="tab"
        @click="changeTab('overview')"
      >
        概览
      </button>
      <button
        type="button"
        class="task-detail__tab"
        :class="{ 'task-detail__tab--active': activeTab === 'config' }"
        data-test="config-tab-trigger"
        role="tab"
        @click="changeTab('config')"
      >
        配置
      </button>
      <button
        type="button"
        class="task-detail__tab"
        :class="{ 'task-detail__tab--active': activeTab === 'history' }"
        data-test="history-tab-trigger"
        role="tab"
        @click="changeTab('history')"
      >
        运行历史
      </button>
    </div>

    <div v-if="activeTab === 'overview'" class="task-detail__panel" data-test="task-overview-tab">
      <div class="task-detail__overview-grid">
        <div class="task-detail__info-block">
          <span class="task-detail__label">生命周期</span>
          <StatusTag :status="task.lifecycleStatus" kind="task" />
        </div>
        <div class="task-detail__info-block">
          <span class="task-detail__label">读取模式</span>
          <strong>{{ task.readMode === 'TABLE' ? 'Table' : 'SQL' }} · 版本 {{ task.version }}</strong>
        </div>
        <div class="task-detail__info-block">
          <span class="task-detail__label">最近运行</span>
          <div v-if="task.latestRun" class="task-detail__latest">
            <StatusTag :status="task.latestRun.status" />
            <span class="task-detail__muted">{{ latestRunMeta() }}</span>
          </div>
          <strong v-else class="task-detail__muted">暂无运行</strong>
        </div>
        <div class="task-detail__info-block">
          <span class="task-detail__label">目标表</span>
          <strong>
            {{ task.targetSchema ? task.targetSchema + '.' : '' }}{{ task.targetTable }}
          </strong>
        </div>
      </div>

      <div v-if="task.lifecycleStatus !== 'DRAFT'" class="task-detail__hint task-detail__hint--lock">
        任务启用后，读取定义、目标表、写入模式、唯一键和字段映射不可直接修改。
        如需调整，请复制或重建任务，避免改变现有 Checkpoint 语义。
      </div>

      <div class="task-detail__actions">
        <el-button
          v-if="task.lifecycleStatus === 'ENABLED'"
          type="warning"
          :disabled="operating"
          data-test="task-pause"
          @click="handlePause"
        >
          暂停
        </el-button>
        <el-button
          v-if="task.lifecycleStatus === 'PAUSED'"
          :disabled="operating"
          data-test="task-resume"
          @click="handleResume"
        >
          继续
        </el-button>
        <el-button
          v-if="['ENABLED', 'PAUSED', 'BLOCKED'].includes(task.lifecycleStatus)"
          type="danger"
          :disabled="operating"
          data-test="task-disable"
          @click="handleDisable"
        >
          禁用
        </el-button>
        <el-button type="primary" :disabled="operating" data-test="start-full" @click="runAction('full')">
          首次全量
        </el-button>
        <el-button
          :disabled="operating || task.writeMode === 'REPLACE_ALL'"
          data-test="start-incremental"
          @click="runAction('incremental')"
        >
          手动增量
        </el-button>
        <el-button data-test="task-validate" @click="handleValidate">运行校验</el-button>
        <el-button data-test="task-edit" @click="router.push({ name: 'task-edit', params: { taskId } })">
          编辑
        </el-button>
      </div>
    </div>

    <div v-else-if="activeTab === 'config'" class="task-detail__panel" data-test="task-config-tab">
      <div class="task-detail__section">
        <h3>读取配置</h3>
        <p v-for="(line, index) in readDefinitionSummary(task)" :key="index" class="task-detail__line">
          {{ line }}
        </p>
      </div>

      <div class="task-detail__section">
        <h3>字段映射（{{ task.fieldMappings.length }}）</h3>
        <table v-if="task.fieldMappings.length > 0" class="task-detail__table">
          <thead>
            <tr><th>源字段</th><th>类型</th><th>目标字段</th><th>类型</th></tr>
          </thead>
          <tbody>
            <tr v-for="(mapping, index) in task.fieldMappings" :key="index">
              <td>{{ mapping.sourceField }}</td>
              <td class="task-detail__type">{{ sourceTypes[mapping.sourceField] ?? '-' }}</td>
              <td>{{ mapping.targetField }}</td>
              <td class="task-detail__type">{{ targetTypes[mapping.targetField] ?? '-' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="task-detail__hint">未配置字段映射</p>
      </div>

      <div class="task-detail__section">
        <h3>Sink 与目标</h3>
        <p class="task-detail__line">Source 端：{{ endpointNames[task.sourceEndpointId ?? ''] ?? task.sourceEndpointId ?? '-' }}</p>
        <p class="task-detail__line">Source 数据源：{{ task.sourceDataSourceId ?? '-' }}</p>
        <p class="task-detail__line">Sink 端：{{ endpointNames[task.sinkEndpointId ?? ''] ?? task.sinkEndpointId ?? '-' }}</p>
        <p class="task-detail__line">Sink 目标数据源：{{ task.targetDataSourceId ?? '-' }}</p>
        <p class="task-detail__line">Sink URL：{{ task.remoteSinkUrl ?? '-' }}</p>
        <p class="task-detail__line">expectedSinkInstanceId：{{ task.expectedSinkInstanceId ?? '-' }}</p>
        <p class="task-detail__line">
          目标表：{{ task.targetSchema ? task.targetSchema + '.' : '' }}{{ task.targetTable }}
        </p>
        <p class="task-detail__line">
          写入模式：{{ writeModeLabel(task) }}
          <span v-if="task.writeMode === 'REPLACE_ALL'" class="task-detail__hint">
            （工具不清表，启动前请人工清空目标表）
          </span>
        </p>
        <p class="task-detail__line">唯一 Key：{{ task.uniqueKeys.join('、') || '-' }}</p>
      </div>

      <div v-if="task.lifecycleStatus !== 'DRAFT'" class="task-detail__hint task-detail__hint--lock">
        任务启用后，读取定义、目标表、写入模式、唯一键和字段映射不可直接修改。
        如需调整，请复制或重建任务，避免改变现有 Checkpoint 语义。
      </div>
    </div>

    <div v-else class="task-detail__panel" data-test="task-history-tab">
      <p v-if="historyLoading && historyResult.items.length === 0" class="task-detail__hint">
        加载中…
      </p>
      <p v-else-if="historyResult.items.length === 0" class="task-detail__hint">暂无运行</p>
      <div v-else class="task-detail__runs">
        <RunSummaryCard v-for="run in historyResult.items" :key="run.runId" :run="run" />
      </div>
    </div>
  </section>
</template>

<style scoped>
.task-detail__tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--mic-border);
}

.task-detail__tab {
  min-height: 40px;
  padding: 0 16px;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--mic-text-secondary);
  background: transparent;
  font-weight: 600;
  cursor: pointer;
}

.task-detail__tab:hover {
  color: var(--mic-primary);
}

.task-detail__tab--active {
  border-bottom-color: var(--mic-primary);
  color: var(--mic-primary);
}

.task-detail__overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.task-detail__info-block {
  display: flex;
  min-height: 76px;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.task-detail__label {
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.task-detail__latest {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.task-detail__muted {
  color: var(--mic-text-secondary);
}

.task-detail__hint {
  color: var(--mic-text-secondary);
}

.task-detail__hint--lock {
  margin: 0 0 16px;
  padding: 10px 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text-secondary);
  background: var(--mic-neutral-soft);
  font-size: 13px;
}

.task-detail__actions {
  display: flex;
  flex-wrap: nowrap;
  overflow-x: auto;
  gap: 10px;
  margin-top: 16px;
}

.task-detail__actions .el-button {
  flex: 0 0 auto;
  white-space: nowrap;
}

.task-detail__section {
  margin-bottom: 14px;
  padding: 16px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.task-detail__section h3 {
  margin: 0 0 10px;
  color: var(--mic-text);
  font-size: 15px;
}

.task-detail__line {
  margin: 4px 0;
  color: var(--mic-text);
  word-break: break-all;
}

.task-detail__type {
  color: var(--mic-text-secondary);
  font-size: 12px;
}

.task-detail__table {
  border-collapse: collapse;
  font-size: 13px;
}

.task-detail__table th,
.task-detail__table td {
  border: 1px solid var(--mic-border);
  padding: 4px 12px;
  text-align: left;
}

.task-detail__runs {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

@media (max-width: 767px) {
  .task-detail__tab {
    flex: 1;
    padding: 0 8px;
  }

  .task-detail__actions {
    flex-wrap: wrap;
  }
}
</style>
