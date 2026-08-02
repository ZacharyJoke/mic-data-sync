<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  getRun,
  getRunActions,
  getRunDiagnosis,
  listRunBatches,
  pauseRun,
  resumeRun,
  retryRun,
  type BatchItem,
  type RunAction,
  type RunDiagnosis,
} from '@/api/runs'
import { validateTask } from '@/api/tasks'
import BatchCardList from '@/components/run/BatchCardList.vue'
import RunDiagnosisPanel from '@/features/runs/RunDiagnosisPanel.vue'
import RunTimeline from '@/features/runs/RunTimeline.vue'
import PageHeader from '@/shared/components/PageHeader.vue'
import StatusTag from '@/shared/components/StatusTag.vue'
import { useRunPolling } from '@/shared/composables/useRunPolling'
import { formatDateTime } from '@/shared/utils/format'

const route = useRoute()
const router = useRouter()
const runId = route.params.runId as string

const diagnosis = ref<RunDiagnosis | null>(null)
const actions = ref<RunAction[]>([])
const batches = ref<BatchItem[]>([])
const batchTotal = ref(0)
const batchPage = ref(1)
const batchSize = ref(20)
const batchLoading = ref(false)
const operating = ref(false)

const {
  data: run,
  refreshing,
  refresh,
  start: startPolling,
  stop: stopPolling,
} = useRunPolling(() => getRun(runId))

const returnTarget = computed(() =>
  typeof route.query.returnTo === 'string' && route.query.returnTo
    ? route.query.returnTo
    : '/runs',
)

const enabledActions = computed(() => actions.value.filter((action) => action.enabled))

function hasAction(type: string): boolean {
  return enabledActions.value.some((action) => action.type === type)
}

onMounted(async () => {
  await Promise.all([loadDiagnosis(), loadActions(), loadBatches(1, batchSize.value)])
  startPolling()
})

onBeforeUnmount(stopPolling)

async function loadDiagnosis() {
  try {
    diagnosis.value = await getRunDiagnosis(runId)
  } catch {
    diagnosis.value = null
  }
}

async function loadActions() {
  try {
    actions.value = (await getRunActions(runId)).actions
  } catch {
    actions.value = []
  }
}

async function loadBatches(page: number, size: number) {
  batchLoading.value = true
  try {
    const result = await listRunBatches(runId, page, size)
    batches.value = result.items
    batchTotal.value = result.total
    batchPage.value = result.page
    batchSize.value = result.size
  } catch {
    batches.value = []
    batchTotal.value = 0
  } finally {
    batchLoading.value = false
  }
}

async function handlePause() {
  if (operating.value) {
    return
  }
  operating.value = true
  try {
    await pauseRun(runId)
    ElMessage.success('已暂停')
    await refresh()
    await loadActions()
  } catch {
    ElMessage.error('暂停失败')
  } finally {
    operating.value = false
  }
}

async function handleResume() {
  if (operating.value) {
    return
  }
  try {
    await ElMessageBox.confirm('继续后复用原 Run 从 Checkpoint 继续执行，确认？', '继续运行', {
      type: 'warning',
    })
  } catch {
    return
  }
  operating.value = true
  try {
    await resumeRun(runId)
    ElMessage.success('已继续')
    await refresh()
    await loadActions()
  } catch {
    ElMessage.error('继续失败')
  } finally {
    operating.value = false
  }
}

async function handleRevalidate() {
  if (operating.value || !run.value) {
    return
  }
  operating.value = true
  try {
    const report = await validateTask(run.value.taskId)
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
    ElMessage.error('重新校验失败')
  } finally {
    operating.value = false
  }
}

async function handleRetry() {
  if (operating.value) {
    return
  }
  operating.value = true
  const idempotencyKey =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `retry-${Date.now()}-${Math.random()}`
  try {
    const result = await retryRun(runId, idempotencyKey)
    ElMessage.success('安全重试已开始')
    await router.push({ name: 'run-detail', params: { runId: result.resourceId } })
  } catch {
    ElMessage.error('重试失败')
  } finally {
    operating.value = false
  }
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
</script>

<template>
  <section class="run-detail" data-test="run-detail">
    <PageHeader title="运行详情">
      <template #actions>
        <router-link class="run-detail__back" :to="returnTarget" data-test="run-back">
          返回
        </router-link>
      </template>
    </PageHeader>

    <div v-if="run" class="run-detail__meta">
      <router-link class="run-detail__task" :to="`/tasks/${run.taskId}`">
        {{ run.taskName }}
      </router-link>
      <span class="run-detail__kind">{{ kindLabel(run.kind) }}</span>
      <StatusTag :status="run.status" />
      <span v-if="refreshing" class="run-detail__refreshing">刷新中…</span>
    </div>

    <div v-if="run" class="run-detail__stats">
      <div class="run-detail__stat">
        <span>读取</span>
        <strong data-test="source-count">{{ run.sourceRowCount }} 行</strong>
      </div>
      <div class="run-detail__stat">
        <span>确认</span>
        <strong data-test="confirmed-count">{{ run.confirmedRowCount }} 行</strong>
      </div>
      <div class="run-detail__stat">
        <span>开始</span>
        <strong>{{ formatDateTime(run.startedAt) }}</strong>
      </div>
      <div class="run-detail__stat">
        <span>结束</span>
        <strong>{{ run.endedAt ? formatDateTime(run.endedAt) : '-' }}</strong>
      </div>
    </div>

    <RunTimeline :run="run" :diagnosis="diagnosis" />

    <RunDiagnosisPanel v-if="diagnosis" :diagnosis="diagnosis" />

    <div class="run-detail__actions">
      <el-button
        v-if="hasAction('PAUSE')"
        :disabled="operating"
        data-test="pause-action"
        @click="handlePause"
      >
        暂停
      </el-button>
      <el-button
        v-if="hasAction('RESUME')"
        :disabled="operating"
        data-test="resume-action"
        @click="handleResume"
      >
        继续
      </el-button>
      <el-button
        v-if="hasAction('REVALIDATE')"
        :disabled="operating"
        data-test="revalidate-action"
        @click="handleRevalidate"
      >
        重新校验
      </el-button>
      <el-button
        v-if="hasAction('RETRY')"
        type="primary"
        :disabled="operating"
        data-test="retry-action"
        @click="handleRetry"
      >
        安全重试
      </el-button>
    </div>

    <div class="run-detail__section">
      <h3>批次（{{ batchTotal }}）</h3>
      <BatchCardList :batches="batches" :loading="batchLoading" :total="batchTotal" />
      <div v-if="batchTotal > 0" class="run-detail__pagination">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :current-page="batchPage"
          :page-size="batchSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="batchTotal"
          @current-change="(page: number) => loadBatches(page, batchSize)"
          @size-change="(size: number) => loadBatches(1, size)"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.run-detail__back {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  padding: 0 14px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-primary);
  background: var(--mic-surface);
  font-weight: 600;
  text-decoration: none;
}

.run-detail__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.run-detail__task {
  color: var(--mic-text);
  font-size: 16px;
  font-weight: 650;
  text-decoration: none;
}

.run-detail__kind {
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.run-detail__refreshing {
  color: var(--mic-primary);
  font-size: 12px;
}

.run-detail__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
  margin: 14px 0;
}

.run-detail__stat {
  display: flex;
  min-height: 64px;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.run-detail__stat span {
  color: var(--mic-text-secondary);
  font-size: 12px;
}

.run-detail__stat strong {
  color: var(--mic-text);
  font-size: 14px;
}

.run-detail__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 16px 0;
}

.run-detail__section {
  margin-top: 16px;
  padding: 16px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.run-detail__section h3 {
  margin: 0 0 10px;
  color: var(--mic-text);
  font-size: 15px;
}

.run-detail__pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 767px) {
  .run-detail__pagination {
    justify-content: center;
  }
}
</style>
