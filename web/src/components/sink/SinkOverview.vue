<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import {
  checkEndpointAuth,
  getEndpointStatus,
  getEndpointSinkToken,
  listEndpoints,
  probeEndpoint,
  type EndpointAuthCheckResult,
  type EndpointItem,
  type EndpointSinkToken,
  type EndpointStatus,
} from '@/api/endpoints'
import { formatDateTime } from '@/shared/utils/format'

const sinkEndpoints = ref<EndpointItem[]>([])
const endpointStatuses = ref<Record<string, EndpointStatus>>({})
const sinkTokenStatus = ref<Record<string, EndpointSinkToken>>({})
const endpointProbing = ref<Record<string, boolean>>({})
const endpointAuthChecking = ref<Record<string, boolean>>({})
const endpointAuthResult = ref<Record<string, EndpointAuthCheckResult | null>>({})

onMounted(reload)

async function reload() {
  try {
    sinkEndpoints.value = await listEndpoints('SINK')
    await Promise.all(
      sinkEndpoints.value.map(async (endpoint) => {
        try {
          sinkTokenStatus.value[endpoint.id] = await getEndpointSinkToken(endpoint.id)
        } catch {
          sinkTokenStatus.value[endpoint.id] = { configured: false, display: '' }
        }
        try {
          endpointStatuses.value[endpoint.id] = await getEndpointStatus(endpoint.id)
        } catch {
          // 远端状态获取失败不影响总览展示
        }
      }),
    )
  } catch {
    sinkEndpoints.value = []
  }
}

async function handleProbe(endpoint: EndpointItem) {
  endpointProbing.value[endpoint.id] = true
  try {
    const result = await probeEndpoint(endpoint.id)
    ElMessage.success(result.message)
    await reload()
  } catch {
    ElMessage.error('探活失败')
  } finally {
    endpointProbing.value[endpoint.id] = false
  }
}

async function handleAuthCheck(endpoint: EndpointItem) {
  endpointAuthChecking.value[endpoint.id] = true
  endpointAuthResult.value[endpoint.id] = null
  try {
    endpointAuthResult.value[endpoint.id] = await checkEndpointAuth(endpoint.id)
  } catch {
    ElMessage.error('批次认证检查失败')
  } finally {
    endpointAuthChecking.value[endpoint.id] = false
  }
}

function endpointStatusLabel(status: string): string {
  return status === 'READY' ? '就绪' : status === 'NOT_READY' ? '未就绪' : status === 'UNREACHABLE' ? '不可达' : '未知'
}

function endpointDetail(endpoint: EndpointItem): {
  message: string | null
  dbaSql: string | null
  tls: boolean
} | null {
  const sink = endpointStatuses.value[endpoint.id]
  if (!sink) {
    return null
  }
  if (sink.dbaSql || (sink.message && sink.capabilityStatus !== 'READY') || sink.tlsInsecureSkipVerify) {
    return { message: sink.message, dbaSql: sink.dbaSql, tls: sink.tlsInsecureSkipVerify }
  }
  return null
}

defineExpose({ reload })
</script>

<template>
  <div class="sink-overview" data-test="sink-overview">
    <div class="sink-overview__table-wrap">
      <table class="sink-overview__table">
        <thead>
          <tr>
            <th>端</th>
            <th>地址</th>
            <th>数据库类型</th>
            <th>状态</th>
            <th>协议</th>
            <th>批次限制</th>
            <th>Sink 令牌</th>
            <th>最近探活</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="endpoint in sinkEndpoints" :key="endpoint.id">
            <tr :data-test="`sink-overview-${endpoint.id}`">
              <td :title="endpoint.name">{{ endpoint.name }}</td>
              <td class="sink-overview__mono" :title="endpoint.baseUrl ?? '-'">{{ endpoint.baseUrl ?? '-' }}</td>
              <td>{{ endpointStatuses[endpoint.id]?.databaseType ?? '-' }}</td>
              <td>
                <span
                  class="sink-overview__badge"
                  :class="endpoint.status === 'READY' ? 'sink-overview__badge--ready' : 'sink-overview__badge--notready'"
                >
                  {{ endpointStatusLabel(endpoint.status) }}
                </span>
              </td>
              <td>v{{ endpointStatuses[endpoint.id]?.protocolVersion ?? '-' }}</td>
              <td class="sink-overview__mono">
                {{
                  endpointStatuses[endpoint.id]
                    ? `${endpointStatuses[endpoint.id]?.batchLimits.maxRowsPerBatch} 行 / ${((endpointStatuses[endpoint.id]?.batchLimits.maxPayloadBytes ?? 0) / 1024 / 1024).toFixed(0)} MB`
                    : '-'
                }}
              </td>
              <td class="sink-overview__mono">
                {{ sinkTokenStatus[endpoint.id]?.display || (sinkTokenStatus[endpoint.id]?.configured ? '已配置' : '未配置') }}
              </td>
              <td class="sink-overview__mono" :title="formatDateTime(endpoint.lastProbeAt)">
                {{ formatDateTime(endpoint.lastProbeAt) }}
              </td>
              <td>
                <div class="sink-overview__actions">
                  <button type="button" :disabled="endpointProbing[endpoint.id]" @click="handleProbe(endpoint)">
                    {{ endpointProbing[endpoint.id] ? '探活中…' : '探活' }}
                  </button>
                  <button
                    type="button"
                    :disabled="endpointAuthChecking[endpoint.id]"
                    @click="handleAuthCheck(endpoint)"
                  >
                    {{ endpointAuthChecking[endpoint.id] ? '检查中…' : '批次认证' }}
                  </button>
                </div>
                <p
                  v-if="endpointAuthResult[endpoint.id]"
                  class="sink-overview__auth"
                  :class="{ 'sink-overview__auth--error': !endpointAuthResult[endpoint.id]?.ok }"
                >
                  {{ endpointAuthResult[endpoint.id]?.message }}：Source
                  {{ endpointAuthResult[endpoint.id]?.sourceDisplay || '部署配置' }} / Sink
                  {{ endpointAuthResult[endpoint.id]?.sinkMasked || '未配置' }}
                  <template v-if="endpointAuthResult[endpoint.id]?.handshake">· 握手 {{ endpointAuthResult[endpoint.id]?.handshake }}</template>
                </p>
              </td>
            </tr>
            <tr v-if="endpointDetail(endpoint)" class="sink-overview__detail-row" :data-test="`sink-detail-${endpoint.id}`">
              <td colspan="9">
                <p v-if="endpointDetail(endpoint)?.tls" class="sink-overview__danger">
                  高风险：已启用 TLS 跳过校验（tlsInsecureSkipVerify=true）
                </p>
                <p v-if="endpointDetail(endpoint)?.message && endpointStatuses[endpoint.id]?.capabilityStatus !== 'READY'">
                  {{ endpointDetail(endpoint)?.message }}
                </p>
                <div v-if="endpointDetail(endpoint)?.dbaSql">
                  <p>请由 DBA 在目标数据库执行以下初始化 SQL：</p>
                  <pre class="sink-overview__dba-sql">{{ endpointDetail(endpoint)?.dbaSql }}</pre>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.sink-overview__table-wrap {
  overflow-x: auto;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}

.sink-overview__table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.sink-overview__table th,
.sink-overview__table td {
  padding: 8px 10px;
  border-bottom: 1px solid #ebeef5;
  text-align: left;
  white-space: nowrap;
}

.sink-overview__table th {
  color: #909399;
  background: #f5f7fa;
  font-size: 12px;
}

.sink-overview__table tr:last-child td {
  border-bottom: none;
}

.sink-overview__mono {
  font-family: var(--mic-font-mono, monospace);
  font-size: 12px;
}

.sink-overview__badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.sink-overview__badge--ready {
  background: #e1f3d8;
  color: #67c23a;
}

.sink-overview__badge--notready {
  background: #fdf6ec;
  color: #e6a23c;
}

.sink-overview__actions {
  display: flex;
  gap: 6px;
}

.sink-overview__actions button {
  height: 28px;
  padding: 0 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  color: #606266;
  cursor: pointer;
}

.sink-overview__auth {
  margin: 6px 0 0;
  padding: 6px 8px;
  border-radius: 4px;
  background: #e1f3d8;
  color: #67c23a;
  font-size: 12px;
}

.sink-overview__auth--error {
  background: #fdf0f0;
  color: #f56c6c;
}

.sink-overview__detail-row td {
  padding: 6px 10px;
  background: #f7f8fa;
  font-size: 12px;
}

.sink-overview__detail-row p {
  margin: 0 0 4px;
}

.sink-overview__danger {
  color: #f56c6c;
  font-weight: 600;
}

.sink-overview__dba-sql {
  margin: 4px 0 0;
  padding: 8px;
  overflow-x: auto;
  border-radius: 4px;
  background: #fff;
  font-family: var(--mic-font-mono, monospace);
  font-size: 12px;
  white-space: pre-wrap;
}
</style>
