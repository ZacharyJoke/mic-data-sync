<script setup lang="ts">
import { MoreFilled, RefreshLeft, Search } from '@element-plus/icons-vue'
import type { AxiosError } from 'axios'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import { toApiErrorInfo, type ApiErrorInfo, type ApiResponse } from '@/api/http'
import {
  deleteTask,
  disableTask,
  enableTask,
  listTasks,
  pauseTask,
  resumeTask,
  validateTask,
  type TaskItem,
} from '@/api/tasks'
import PageHeader from '@/shared/components/PageHeader.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import type { PageResult } from '@/shared/api/page'
import { usePagedRouteQuery } from '@/shared/composables/usePagedRouteQuery'
import { formatDateTime } from '@/shared/utils/format'

const LIFECYCLE_OPTIONS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'ENABLED', label: '已启用' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'DISABLED', label: '已禁用' },
  { value: 'BLOCKED', label: '已阻塞' },
]

const READ_MODE_OPTIONS = [
  { value: 'TABLE', label: 'Table' },
  { value: 'SQL', label: 'SQL' },
]

const LATEST_RUN_STATUS_OPTIONS = [
  { value: 'RUNNING', label: '运行中' },
  { value: 'WAITING_RETRY', label: '等待重试' },
  { value: 'UNKNOWN', label: '结果未知' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'CANCELLED', label: '已取消' },
]

const route = useRoute()
const router = useRouter()
const { query, setQuery } = usePagedRouteQuery(router, route, {
  page: 1,
  size: 20,
  keyword: '',
  lifecycleStatus: '',
  readMode: '',
  latestRunStatus: '',
})

const result = ref<PageResult<TaskItem>>({ items: [], total: 0, page: 1, size: 20 })
const loading = ref(false)
const requestError = ref<ApiErrorInfo | null>(null)
const keywordDraft = ref('')

const lifecycleStatus = computed({
  get: () => query.value.lifecycleStatus,
  set: (value: string) => void setQuery({ page: 1, lifecycleStatus: value ?? '' }),
})

const readMode = computed({
  get: () => query.value.readMode,
  set: (value: string) => void setQuery({ page: 1, readMode: value ?? '' }),
})

const latestRunStatus = computed({
  get: () => query.value.latestRunStatus,
  set: (value: string) => void setQuery({ page: 1, latestRunStatus: value ?? '' }),
})

watch(
  query,
  () => {
    keywordDraft.value = query.value.keyword
    void loadTasks()
  },
  { immediate: true },
)

async function loadTasks() {
  loading.value = true
  requestError.value = null
  try {
    const q = query.value
    result.value = await listTasks(q.page, q.size, {
      keyword: q.keyword || undefined,
      lifecycleStatus: q.lifecycleStatus || undefined,
      readMode: q.readMode || undefined,
      latestRunStatus: q.latestRunStatus || undefined,
    })
  } catch (error) {
    requestError.value = toApiErrorInfo(error as AxiosError<ApiResponse>)
  } finally {
    loading.value = false
  }
}

function applyKeyword() {
  void setQuery({ page: 1, keyword: keywordDraft.value.trim() })
}

function resetFilters() {
  void setQuery({
    page: 1,
    keyword: '',
    lifecycleStatus: '',
    readMode: '',
    latestRunStatus: '',
  })
}

function handlePageChange(page: number) {
  void setQuery({ page })
}

function handleSizeChange(size: number) {
  void setQuery({ page: 1, size })
}

async function handleValidate(task: TaskItem) {
  try {
    const report = await validateTask(task.taskId)
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

async function handleEnable(task: TaskItem) {
  try {
    await enableTask(task.taskId)
    ElMessage.success('任务已启用')
    await loadTasks()
  } catch {
    ElMessage.error('启用失败，请先运行校验查看原因')
  }
}

async function handlePause(task: TaskItem) {
  try {
    await pauseTask(task.taskId)
    ElMessage.success('任务已暂停')
    await loadTasks()
  } catch {
    ElMessage.error('暂停失败（仅已启用任务可暂停）')
  }
}

async function handleResume(task: TaskItem) {
  try {
    await resumeTask(task.taskId)
    ElMessage.success('任务已继续')
    await loadTasks()
  } catch {
    ElMessage.error('继续失败（仅已暂停任务可继续）')
  }
}

async function handleDisable(task: TaskItem) {
  try {
    await ElMessageBox.confirm(`确认禁用任务「${task.name}」？禁用后不能触发运行。`, '禁用确认', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await disableTask(task.taskId)
    ElMessage.success('任务已禁用')
    await loadTasks()
  } catch {
    ElMessage.error('禁用失败（可能存在活动 Run）')
  }
}

async function handleDelete(task: TaskItem) {
  try {
    await ElMessageBox.confirm(`确认删除任务「${task.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteTask(task.taskId)
    ElMessage.success('任务已删除')
    await loadTasks()
  } catch {
    ElMessage.error('删除失败（可能存在活动运行）')
  }
}

function directionLabel(task: TaskItem): string {
  const source =
    task.readMode === 'SQL'
      ? (task.readDefinition?.baseTable as string | undefined) ?? 'SQL'
      : (task.readDefinition?.table as string | undefined) ?? 'Table'
  return `${source} → ${task.targetSchema ? task.targetSchema + '.' : ''}${task.targetTable}`
}

function latestRunText(task: TaskItem): string {
  if (!task.latestRun) {
    return '暂无运行'
  }
  return `${task.latestRun.kind} · ${formatDateTime(task.latestRun.startedAt)}`
}

function formatTime(value: string): string {
  return formatDateTime(value)
}
</script>

<template>
  <section class="task-list" data-test="task-list">
    <PageHeader title="同步任务">
      <template #actions>
        <router-link
          class="task-list__create"
          data-test="task-create-link"
          to="/tasks/new"
        >
          新建任务
        </router-link>
      </template>
    </PageHeader>

    <div v-if="requestError" class="task-list__error" role="alert" data-test="task-list-error">
      {{ requestError.message }}
    </div>

    <div class="task-list__filters" data-test="task-filters">
      <el-input
        v-model="keywordDraft"
        class="task-list__filter task-list__filter--keyword"
        placeholder="任务名称关键词"
        clearable
        data-test="task-keyword"
        @keyup.enter="applyKeyword"
        @clear="applyKeyword"
      />
      <el-select
        v-model="lifecycleStatus"
        class="task-list__filter"
        placeholder="生命周期"
        clearable
        data-test="task-lifecycle"
      >
        <el-option
          v-for="item in LIFECYCLE_OPTIONS"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-select
        v-model="readMode"
        class="task-list__filter"
        placeholder="读取模式"
        clearable
        data-test="task-read-mode"
      >
        <el-option
          v-for="item in READ_MODE_OPTIONS"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-select
        v-model="latestRunStatus"
        class="task-list__filter"
        placeholder="最近运行状态"
        clearable
        data-test="task-latest-run"
      >
        <el-option
          v-for="item in LATEST_RUN_STATUS_OPTIONS"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-button type="primary" :icon="Search" data-test="task-filter-submit" @click="applyKeyword">
        查询
      </el-button>
      <el-button :icon="RefreshLeft" data-test="task-filter-reset" @click="resetFilters">
        重置
      </el-button>
    </div>

    <div class="task-list__summary">
      <span data-test="task-total">共 {{ result.total }} 条</span>
      <span v-if="loading" class="task-list__loading">加载中…</span>
    </div>

    <div class="task-list__table-wrap">
      <el-table
        v-loading="loading"
        :data="result.items"
        border
        data-test="task-table"
        :empty-text="requestError ? '查询失败' : '暂无任务'"
      >
        <el-table-column label="任务名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <router-link class="task-list__name" :to="`/tasks/${row.taskId}`">
              {{ row.name }}
            </router-link>
          </template>
        </el-table-column>
        <el-table-column label="读取模式" width="100">
          <template #default="{ row }">{{ row.readMode === 'SQL' ? 'SQL' : 'Table' }}</template>
        </el-table-column>
        <el-table-column label="目标表" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.targetSchema ? row.targetSchema + '.' : '' }}{{ row.targetTable }}
          </template>
        </el-table-column>
        <el-table-column label="生命周期" width="112">
          <template #default="{ row }">
            <StatusTag :status="row.lifecycleStatus" kind="task" />
          </template>
        </el-table-column>
        <el-table-column label="最近运行" min-width="210">
          <template #default="{ row }">
            <div v-if="row.latestRun" class="task-list__latest-cell">
              <StatusTag :status="row.latestRun.status" />
              <span class="task-list__latest-time" :title="latestRunText(row)">{{ latestRunText(row) }}</span>
            </div>
            <span v-else class="task-list__muted">暂无运行</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <div class="task-list__actions">
              <router-link class="task-list__action-link" :to="`/tasks/${row.taskId}`">
                详情
              </router-link>
              <el-button text type="primary" size="small" @click="handleValidate(row)">校验</el-button>
              <el-button
                v-if="row.lifecycleStatus === 'DRAFT' || row.lifecycleStatus === 'DISABLED'"
                text
                type="primary"
                size="small"
                @click="handleEnable(row)"
              >
                启用
              </el-button>
              <el-button
                v-if="row.lifecycleStatus === 'ENABLED'"
                text
                type="warning"
                size="small"
                data-test="task-pause"
                @click="handlePause(row)"
              >
                暂停
              </el-button>
              <el-button
                v-if="row.lifecycleStatus === 'PAUSED'"
                text
                type="primary"
                size="small"
                data-test="task-resume"
                @click="handleResume(row)"
              >
                继续
              </el-button>
              <el-button
                v-if="['ENABLED', 'PAUSED', 'BLOCKED'].includes(row.lifecycleStatus)"
                text
                type="danger"
                size="small"
                data-test="task-disable"
                @click="handleDisable(row)"
              >
                禁用
              </el-button>
              <el-dropdown trigger="click" @command="(command: string) => command === 'delete' && handleDelete(row)">
                <el-button text circle :icon="MoreFilled" size="small" />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="delete">删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="result.items.length > 0" class="task-list__mobile" data-test="task-mobile-list">
      <p class="task-list__mobile-note">请在平板或桌面完成任务配置</p>
      <div v-for="task in result.items" :key="task.taskId" class="task-list__mobile-card">
        <div class="task-list__mobile-header">
          <router-link class="task-list__name" :to="`/tasks/${task.taskId}`">
            {{ task.name }}
          </router-link>
          <StatusTag :status="task.lifecycleStatus" kind="task" />
        </div>
        <p class="task-list__mobile-meta">{{ directionLabel(task) }}</p>
        <p class="task-list__mobile-meta">{{ latestRunText(task) }}</p>
        <div class="task-list__mobile-actions">
          <router-link class="task-list__action-link" :to="`/tasks/${task.taskId}`">
            详情
          </router-link>
          <el-button text type="primary" size="small" @click="handleValidate(task)">校验</el-button>
          <el-button
            v-if="task.lifecycleStatus === 'DRAFT' || task.lifecycleStatus === 'DISABLED'"
            text
            type="primary"
            size="small"
            @click="handleEnable(task)"
          >
            启用
          </el-button>
          <el-button
            v-if="task.lifecycleStatus === 'ENABLED'"
            text
            type="warning"
            size="small"
            @click="handlePause(task)"
          >
            暂停
          </el-button>
          <el-button
            v-if="task.lifecycleStatus === 'PAUSED'"
            text
            type="primary"
            size="small"
            @click="handleResume(task)"
          >
            继续
          </el-button>
          <el-button
            v-if="['ENABLED', 'PAUSED', 'BLOCKED'].includes(task.lifecycleStatus)"
            text
            type="danger"
            size="small"
            @click="handleDisable(task)"
          >
            禁用
          </el-button>
        </div>
      </div>
    </div>

    <div class="task-list__pagination">
      <el-pagination
        background
        layout="total, sizes, prev, pager, next"
        :current-page="result.page"
        :page-size="result.size"
        :page-sizes="[10, 20, 50, 100]"
        :total="result.total"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>
  </section>
</template>

<style scoped>
.task-list__error {
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--mic-danger);
  border-radius: var(--mic-radius);
  color: var(--mic-danger);
  background: var(--mic-danger-soft);
}

.task-list__create {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  padding: 0 14px;
  border-radius: var(--mic-radius);
  color: #ffffff;
  background: var(--mic-primary);
  font-weight: 600;
  text-decoration: none;
}

.task-list__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.task-list__filter {
  width: 150px;
}

.task-list__filter--keyword {
  width: 220px;
}

.task-list__summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 28px;
  margin-bottom: 8px;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.task-list__loading {
  color: var(--mic-primary);
}

.task-list__table-wrap {
  overflow-x: auto;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.task-list__name {
  color: var(--mic-text);
  font-weight: 600;
  text-decoration: none;
}

.task-list__latest-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-list__latest-time {
  overflow: hidden;
  color: var(--mic-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-list__muted {
  color: var(--mic-text-secondary);
}

.task-list__actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.task-list__action-link {
  color: var(--mic-primary);
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
}

.task-list__mobile {
  display: none;
}

.task-list__mobile-note {
  margin: 0 0 10px;
  padding: 10px 12px;
  border-radius: var(--mic-radius);
  color: var(--mic-text-secondary);
  background: var(--mic-neutral-soft);
  font-size: 13px;
}

.task-list__mobile-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 10px;
  padding: 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.task-list__mobile-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.task-list__mobile-meta {
  margin: 0;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.task-list__mobile-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.task-list__pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

@media (max-width: 767px) {
  .task-list__create {
    display: none;
  }

  .task-list__filter {
    width: calc(50% - 4px);
  }

  .task-list__filter--keyword {
    width: 100%;
  }

  .task-list__table-wrap {
    display: none;
  }

  .task-list__mobile {
    display: block;
  }

  .task-list__pagination {
    justify-content: center;
  }
}
</style>
