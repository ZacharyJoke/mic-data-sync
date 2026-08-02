<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import type { ConnectionSummary } from '@/features/dashboard/api'
import { getDashboardSummary, type DashboardSummary } from '@/features/dashboard/api'
import PageHeader from '@/shared/components/PageHeader.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { formatDateTime } from '@/shared/utils/format'
import SinkOverview from '@/components/sink/SinkOverview.vue'

const route = useRoute()
const summary = ref<DashboardSummary | null>(null)
const loading = ref(true)

onMounted(load)

async function load() {
  loading.value = true
  try {
    summary.value = await getDashboardSummary()
  } catch {
    // 加载失败时保留已渲染数据
  } finally {
    loading.value = false
  }
}

function successRateText(): string {
  const rate = summary.value?.todaySuccessRate
  if (rate === null || rate === undefined) {
    return '暂无数据'
  }
  return `${Math.round(rate * 100)}%`
}

function connectionText(connection: ConnectionSummary | undefined): string {
  if (!connection) {
    return '-'
  }
  if (!connection.configured) {
    return '未配置'
  }
  return `${connection.product ?? '数据库'} · ${connection.healthy ? '正常' : '异常'}`
}

function connectionTone(connection: ConnectionSummary | undefined): string {
  if (!connection || !connection.configured || !connection.healthy) {
    return 'dashboard-env__item--warn'
  }
  return 'dashboard-env__item--ok'
}

function kindLabel(kind: string): string {
  const labels: Record<string, string> = {
    INITIAL_FULL: '首次全量',
    CATCH_UP: '自动追赶',
    INCREMENTAL: '增量',
    MANUAL: '手动',
  }
  return labels[kind] ?? kind
}

function formatTime(value: string): string {
  return formatDateTime(value)
}

function detailTarget(runId: string) {
  return {
    name: 'run-detail',
    params: { runId },
    query: { returnTo: route.fullPath },
  }
}
</script>

<template>
  <section class="dashboard-view" data-test="dashboard-view">
    <PageHeader title="工作台" />

    <div class="dashboard-view__section">
      <h2 class="dashboard-section__title">快捷操作</h2>
      <div class="dashboard-view__quick">
        <router-link class="dashboard-quick-link" to="/tasks/new" data-test="quick-create-task">
          新建任务
        </router-link>
        <router-link class="dashboard-quick-link" to="/runs" data-test="quick-open-runs">
          运行记录
        </router-link>
        <router-link class="dashboard-quick-link" to="/data-sources" data-test="quick-data-sources">
          数据源
        </router-link>
      </div>
    </div>

    <p v-if="loading && !summary" class="dashboard-view__hint">加载中…</p>

    <div v-if="summary" class="dashboard-view__env" data-test="dashboard-env">
      <div class="dashboard-env__item" :class="connectionTone(summary.source)" data-test="source-status">
        <span class="dashboard-env__label">Source 数据库</span>
        <strong>{{ connectionText(summary.source) }}</strong>
        <p>{{ summary.source.message }}</p>
      </div>
      <div class="dashboard-env__item" :class="connectionTone(summary.sink)" data-test="sink-db-status">
        <span class="dashboard-env__label">Sink 数据库</span>
        <strong>{{ connectionText(summary.sink) }}</strong>
        <p>{{ summary.sink.message }}</p>
      </div>
      <div class="dashboard-env__item dashboard-env__item--ok" data-test="instance-status">
        <span class="dashboard-env__label">实例</span>
        <strong>READY · {{ summary.instance.roles }}</strong>
        <p class="dashboard-env__mono">{{ summary.instance.instanceId }}</p>
      </div>
    </div>

    <div v-if="summary" class="dashboard-view__metrics">
      <div class="dashboard-metric" data-test="metric-enabled-tasks">
        <span class="dashboard-metric__label">已启用任务</span>
        <strong class="dashboard-metric__value">{{ summary.enabledTaskCount }}</strong>
      </div>
      <div class="dashboard-metric" data-test="metric-active-runs">
        <span class="dashboard-metric__label">活动运行</span>
        <strong class="dashboard-metric__value">{{ summary.activeRunCount }}</strong>
      </div>
      <div class="dashboard-metric" data-test="metric-success-rate">
        <span class="dashboard-metric__label">今日成功率</span>
        <strong class="dashboard-metric__value">{{ successRateText() }}</strong>
      </div>
      <div class="dashboard-metric" data-test="metric-unresolved">
        <span class="dashboard-metric__label">待处理异常</span>
        <strong class="dashboard-metric__value">{{ summary.unresolvedFailureCount }}</strong>
      </div>
    </div>

    <div v-if="summary" class="dashboard-view__section">
      <h2 class="dashboard-section__title">Sink 端总览</h2>
      <SinkOverview />
    </div>

    <div v-if="summary" class="dashboard-view__section">
      <h2 class="dashboard-section__title">待处理异常</h2>
      <p v-if="summary.alerts.length === 0" class="dashboard-view__hint">暂无待处理异常</p>
      <div v-else class="dashboard-alert-list">
        <div v-for="alert in summary.alerts" :key="alert.runId" class="dashboard-alert">
          <div class="dashboard-alert__main">
            <strong :title="alert.taskName">{{ alert.taskName }}</strong>
            <p :title="alert.summary">{{ alert.summary }}</p>
            <span class="dashboard-alert__time">{{ formatTime(alert.occurredAt) }}</span>
          </div>
          <router-link
            class="dashboard-alert__action"
            data-test="open-diagnosis"
            :to="detailTarget(alert.runId)"
          >
            查看诊断
          </router-link>
        </div>
      </div>
    </div>

    <div v-if="summary" class="dashboard-view__section">
      <h2 class="dashboard-section__title">最近运行</h2>
      <p v-if="summary.recentRuns.length === 0" class="dashboard-view__hint">暂无运行记录</p>
      <div v-else class="dashboard-run-list">
        <div v-for="run in summary.recentRuns" :key="run.runId" class="dashboard-run">
          <div class="dashboard-run__main">
            <router-link class="dashboard-run__name" :to="detailTarget(run.runId)">
              {{ run.taskName }}
            </router-link>
            <span class="dashboard-run__meta">
              {{ kindLabel(run.kind) }} · {{ formatTime(run.startedAt) }} ·
              读取 {{ run.sourceRowCount }} / 确认 {{ run.confirmedRowCount }}
            </span>
          </div>
          <StatusTag :status="run.status" />
        </div>
      </div>
    </div>

  </section>
</template>

<style scoped>
.dashboard-view__hint {
  color: var(--mic-text-secondary);
}

.dashboard-view__env {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.dashboard-env__item {
  min-height: 96px;
  padding: 14px;
  border: 1px solid var(--mic-border);
  border-left: 4px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.dashboard-env__item--ok {
  border-left-color: var(--mic-success);
}

.dashboard-env__item--warn {
  border-left-color: var(--mic-warning);
}

.dashboard-env__label {
  display: block;
  margin-bottom: 6px;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.dashboard-env__item strong {
  color: var(--mic-text);
  font-size: 15px;
}

.dashboard-env__item p {
  margin: 6px 0 0;
  color: var(--mic-text-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.dashboard-env__mono {
  overflow: hidden;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-view__metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}

.dashboard-metric {
  padding: 16px 14px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.dashboard-metric__label {
  display: block;
  margin-bottom: 8px;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.dashboard-metric__value {
  color: var(--mic-text);
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
}

.dashboard-view__section {
  margin-bottom: 22px;
}

.dashboard-section__title {
  margin: 0 0 10px;
  color: var(--mic-text);
  font-size: 16px;
  font-weight: 650;
}

.dashboard-alert-list,
.dashboard-run-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.dashboard-alert,
.dashboard-run {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.dashboard-alert {
  border-left: 4px solid var(--mic-danger);
}

.dashboard-alert__main {
  min-width: 0;
}

.dashboard-alert__main strong {
  color: var(--mic-text);
  font-size: 14px;
}

.dashboard-alert__main p {
  margin: 4px 0;
  color: var(--mic-text-secondary);
  font-size: 13px;
  word-break: break-all;
}

.dashboard-alert__time {
  color: var(--mic-text-secondary);
  font-size: 12px;
}

.dashboard-alert__action,
.dashboard-quick-link {
  flex: 0 0 auto;
  padding: 7px 12px;
  border-radius: var(--mic-radius);
  color: var(--mic-primary);
  background: var(--mic-primary-soft);
  font-weight: 600;
  text-decoration: none;
}

.dashboard-run__main {
  min-width: 0;
}

.dashboard-run__name {
  color: var(--mic-text);
  font-weight: 600;
  text-decoration: none;
}

.dashboard-run__meta {
  display: block;
  margin-top: 4px;
  color: var(--mic-text-secondary);
  font-size: 12px;
}

.dashboard-view__quick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

@media (max-width: 767px) {
  .dashboard-alert,
  .dashboard-run {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
