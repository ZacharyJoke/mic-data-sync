<script setup lang="ts">
import { RefreshLeft, Search } from '@element-plus/icons-vue'
import type { AxiosError } from 'axios'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { toApiErrorInfo, type ApiErrorInfo, type ApiResponse } from '@/api/http'
import { listRuns, type RunItem } from '@/api/runs'
import { listTasks, type TaskItem } from '@/api/tasks'
import PageHeader from '@/shared/components/PageHeader.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import type { PageResult } from '@/shared/api/page'
import { usePagedRouteQuery } from '@/shared/composables/usePagedRouteQuery'
import { formatDateTime } from '@/shared/utils/format'

const RUN_STATUS_OPTIONS = [
  { value: 'RUNNING', label: '运行中' },
  { value: 'WAITING_RETRY', label: '等待重试' },
  { value: 'UNKNOWN', label: '结果未知' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'CANCELLED', label: '已取消' },
]

const RUN_KIND_OPTIONS = [
  { value: 'INITIAL_FULL', label: '首次全量' },
  { value: 'CATCH_UP', label: '自动追赶' },
  { value: 'INCREMENTAL', label: '增量' },
  { value: 'MANUAL', label: '手动' },
]

const route = useRoute()
const router = useRouter()
const { query, setQuery } = usePagedRouteQuery(router, route, {
  page: 1,
  size: 20,
  status: '',
  taskId: '',
  kind: '',
  startedFrom: '',
  startedTo: '',
  keyword: '',
})

const result = ref<PageResult<RunItem>>({ items: [], total: 0, page: 1, size: 20 })
const loading = ref(false)
const requestError = ref<ApiErrorInfo | null>(null)
const taskOptions = ref<TaskItem[]>([])
const keywordDraft = ref('')

const status = computed({
  get: () => query.value.status,
  set: (value: string) => void setQuery({ page: 1, status: value ?? '' }),
})

const taskId = computed({
  get: () => query.value.taskId,
  set: (value: string) => void setQuery({ page: 1, taskId: value ?? '' }),
})

const kind = computed({
  get: () => query.value.kind,
  set: (value: string) => void setQuery({ page: 1, kind: value ?? '' }),
})

const dateRange = computed({
  get: (): [string, string] | null =>
    query.value.startedFrom && query.value.startedTo
      ? [query.value.startedFrom, query.value.startedTo]
      : null,
  set: (range: [string, string] | null) => {
    if (range) {
      void setQuery({ page: 1, startedFrom: range[0], startedTo: range[1] })
    } else {
      void setQuery({ page: 1, startedFrom: '', startedTo: '' })
    }
  },
})

watch(
  query,
  () => {
    keywordDraft.value = query.value.keyword
    void loadRuns()
  },
  { immediate: true },
)

onMounted(loadTaskOptions)

async function loadRuns() {
  loading.value = true
  requestError.value = null
  try {
    const q = query.value
    result.value = await listRuns(q.page, q.size, {
      status: q.status || undefined,
      taskId: q.taskId || undefined,
      kind: q.kind || undefined,
      startedFrom: q.startedFrom || undefined,
      startedTo: q.startedTo || undefined,
      keyword: q.keyword || undefined,
    })
  } catch (error) {
    requestError.value = toApiErrorInfo(error as AxiosError<ApiResponse>)
  } finally {
    loading.value = false
  }
}

async function loadTaskOptions() {
  try {
    taskOptions.value = (await listTasks(1, 100)).items
  } catch {
    taskOptions.value = []
  }
}

function applyKeyword() {
  void setQuery({ page: 1, keyword: keywordDraft.value.trim() })
}

function resetFilters() {
  void setQuery({
    page: 1,
    status: '',
    taskId: '',
    kind: '',
    startedFrom: '',
    startedTo: '',
    keyword: '',
  })
}

function handlePageChange(page: number) {
  void setQuery({ page })
}

function handleSizeChange(size: number) {
  void setQuery({ page: 1, size })
}

function kindLabel(kindValue: string): string {
  return RUN_KIND_OPTIONS.find((item) => item.value === kindValue)?.label ?? kindValue
}

function formatTime(value: string): string {
  return formatDateTime(value)
}

function detailTarget(run: RunItem) {
  return {
    name: 'run-detail',
    params: { runId: run.runId },
    query: { returnTo: route.fullPath },
  }
}
</script>

<template>
  <section class="run-list" data-test="run-list">
    <PageHeader title="运行记录" />

    <div v-if="requestError" class="run-list__error" role="alert" data-test="run-list-error">
      {{ requestError.message }}
    </div>

    <div class="run-list__filters" data-test="run-filters">
      <el-select
        v-model="status"
        class="run-list__filter"
        placeholder="状态"
        clearable
        data-test="filter-status"
      >
        <el-option
          v-for="item in RUN_STATUS_OPTIONS"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-select
        v-model="taskId"
        class="run-list__filter run-list__filter--task"
        placeholder="任务"
        clearable
        filterable
        data-test="filter-task"
      >
        <el-option v-for="task in taskOptions" :key="task.taskId" :label="task.name" :value="task.taskId" />
      </el-select>
      <el-select
        v-model="kind"
        class="run-list__filter"
        placeholder="类型"
        clearable
        data-test="filter-kind"
      >
        <el-option
          v-for="item in RUN_KIND_OPTIONS"
          :key="item.value"
          :label="item.label"
          :value="item.value"
        />
      </el-select>
      <el-date-picker
        v-model="dateRange"
        class="run-list__filter run-list__filter--range"
        type="datetimerange"
        value-format="YYYY-MM-DDTHH:mm:ss[Z]"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        clearable
        data-test="filter-range"
      />
      <el-input
        v-model="keywordDraft"
        class="run-list__filter run-list__filter--keyword"
        placeholder="任务名称关键词"
        clearable
        data-test="filter-keyword"
        @keyup.enter="applyKeyword"
        @clear="applyKeyword"
      />
      <el-button type="primary" :icon="Search" data-test="filter-apply" @click="applyKeyword">
        查询
      </el-button>
      <el-button :icon="RefreshLeft" data-test="filter-reset" @click="resetFilters">重置</el-button>
    </div>

    <div class="run-list__summary">
      <span data-test="run-total">共 {{ result.total }} 条</span>
      <span v-if="loading" class="run-list__loading">加载中…</span>
    </div>

    <div class="run-list__table-wrap">
      <el-table
        v-loading="loading"
        :data="result.items"
        border
        data-test="run-table"
        :empty-text="requestError ? '查询失败' : '暂无运行记录'"
      >
        <el-table-column prop="taskName" label="任务" min-width="170" show-overflow-tooltip />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">{{ kindLabel(row.kind) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="112">
          <template #default="{ row }">
            <StatusTag :status="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="开始" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="结束" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.endedAt ? formatTime(row.endedAt) : '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="sourceRowCount" label="读取行数" width="100" align="right" />
        <el-table-column prop="confirmedRowCount" label="确认行数" width="100" align="right" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <router-link
              class="run-list__detail-link"
              data-test="run-row-link"
              :to="detailTarget(row)"
            >
              详情
            </router-link>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <ul v-if="result.items.length > 0" class="run-list__mobile" data-test="run-mobile-list">
      <li v-for="run in result.items" :key="run.runId" class="run-list__mobile-item">
        <div class="run-list__mobile-top">
          <strong class="run-list__mobile-name">{{ run.taskName }}</strong>
          <StatusTag :status="run.status" />
        </div>
        <p class="run-list__mobile-meta">
          {{ kindLabel(run.kind) }} · {{ formatTime(run.startedAt) }}
        </p>
        <p class="run-list__mobile-meta">
          读取 {{ run.sourceRowCount }} · 确认 {{ run.confirmedRowCount }}
        </p>
        <router-link
          class="run-list__detail-link"
          :to="detailTarget(run)"
          data-test="run-row-link-mobile"
        >
          详情
        </router-link>
      </li>
    </ul>

    <div class="run-list__pagination">
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
.run-list__error {
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--mic-danger);
  border-radius: var(--mic-radius);
  color: var(--mic-danger);
  background: var(--mic-danger-soft);
}

.run-list__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.run-list__filter {
  width: 140px;
}

.run-list__filter--task {
  width: 190px;
}

.run-list__filter--range {
  width: 360px;
}

.run-list__filter--keyword {
  width: 220px;
}

.run-list__summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 28px;
  margin-bottom: 8px;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.run-list__loading {
  color: var(--mic-primary);
}

.run-list__table-wrap {
  overflow-x: auto;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.run-list__detail-link {
  color: var(--mic-primary);
  font-weight: 600;
  text-decoration: none;
}

.run-list__mobile {
  display: none;
  margin: 0;
  padding: 0;
  list-style: none;
}

.run-list__mobile-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 10px;
  padding: 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.run-list__mobile-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.run-list__mobile-name {
  color: var(--mic-text);
  font-size: 14px;
}

.run-list__mobile-meta {
  margin: 0;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.run-list__pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

@media (max-width: 767px) {
  .run-list__filter {
    width: calc(50% - 4px);
  }

  .run-list__filter--range {
    width: 100%;
  }

  .run-list__filter--keyword {
    width: 100%;
  }

  .run-list__table-wrap {
    display: none;
  }

  .run-list__mobile {
    display: block;
  }

  .run-list__pagination {
    justify-content: center;
  }
}
</style>
