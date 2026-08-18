<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  checkEndpointAuth,
  createEndpoint,
  deleteEndpoint,
  listEndpoints,
  probeEndpoint,
  updateEndpoint,
  type EndpointAuthCheckResult,
  type EndpointItem,
} from '@/api/endpoints'
import { rotateSinkToken } from '@/api/sink'
import { formatDateTime } from '@/shared/utils/format'

const endpoints = ref<EndpointItem[]>([])
const loading = ref(false)
const probing = ref<Record<string, boolean>>({})
const authChecking = ref<Record<string, boolean>>({})
const authResult = ref<Record<string, EndpointAuthCheckResult | null>>({})
const generatedSinkToken = ref('')
const editing = ref<EndpointItem | null>(null)
const showForm = ref(false)
const form = ref({ name: '', baseUrl: '', sinkToken: '' })

onMounted(load)

async function load() {
  loading.value = true
  try {
    endpoints.value = await listEndpoints()
  } catch {
    ElMessage.error('端列表加载失败')
  } finally {
    loading.value = false
  }
}

function sourceEndpoints(): EndpointItem[] {
  return endpoints.value.filter((endpoint) => endpoint.role === 'SOURCE')
}

function sinkEndpoints(): EndpointItem[] {
  return endpoints.value.filter((endpoint) => endpoint.role === 'SINK')
}

function statusLabel(status: string): string {
  if (status === 'READY') {
    return '就绪'
  }
  if (status === 'NOT_READY') {
    return '未就绪'
  }
  if (status === 'UNREACHABLE') {
    return '不可达'
  }
  return '未知'
}

function openCreate() {
  editing.value = null
  form.value = { name: '', baseUrl: '', sinkToken: '' }
  generatedSinkToken.value = ''
  showForm.value = true
}

function openEdit(endpoint: EndpointItem) {
  editing.value = endpoint
  form.value = {
    name: endpoint.name,
    baseUrl: endpoint.baseUrl ?? '',
    sinkToken: '',
  }
  generatedSinkToken.value = ''
  showForm.value = true
}

async function handleSave() {
  if (!form.value.name.trim() || !form.value.baseUrl.trim()) {
    ElMessage.warning('请填写端名称和访问地址')
    return
  }
  if (editing.value?.isSelf && !form.value.sinkToken.trim()) {
    ElMessage.warning('本地端更新需填写 Sink 访问令牌')
    return
  }
  if (!editing.value && !form.value.sinkToken.trim()) {
    ElMessage.warning('请填写 Sink 访问令牌')
    return
  }
  try {
    if (editing.value) {
      await updateEndpoint(editing.value.id, {
        name: form.value.name.trim(),
        baseUrl: form.value.baseUrl.trim(),
        sinkToken: form.value.sinkToken || undefined,
      })
      ElMessage.success('端已更新')
    } else {
      await createEndpoint({
        name: form.value.name.trim(),
        baseUrl: form.value.baseUrl.trim(),
        sinkToken: form.value.sinkToken.trim(),
      })
      ElMessage.success('端已创建，请探活确认实例信息')
    }
    showForm.value = false
    generatedSinkToken.value = ''
    await load()
  } catch {
    ElMessage.error('保存失败（管理令牌必填，且端名称不能重复）')
  }
}

async function handleDelete(endpoint: EndpointItem) {
  try {
    await ElMessageBox.confirm(`确认删除端「${endpoint.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteEndpoint(endpoint.id)
    ElMessage.success('端已删除')
    await load()
  } catch {
    ElMessage.error('删除失败（端可能已被数据源或任务引用）')
  }
}

async function handleProbe(endpoint: EndpointItem) {
  probing.value[endpoint.id] = true
  try {
    const result = await probeEndpoint(endpoint.id)
    ElMessage.success(result.message)
    await load()
  } catch {
    ElMessage.error('探活请求失败')
  } finally {
    probing.value[endpoint.id] = false
  }
}

async function handleAuthCheck(endpoint: EndpointItem) {
  authChecking.value[endpoint.id] = true
  authResult.value[endpoint.id] = null
  try {
    authResult.value[endpoint.id] = await checkEndpointAuth(endpoint.id)
  } catch {
    ElMessage.error('批次认证检查请求失败')
  } finally {
    authChecking.value[endpoint.id] = false
  }
}

async function handleGenerateSinkToken() {
  try {
    const result = await rotateSinkToken()
    form.value.sinkToken = result.generated
    generatedSinkToken.value = result.generated
    ElMessage.success('已生成本机新 Sink 令牌')
  } catch {
    ElMessage.error('生成失败')
  }
}

async function handleCopyGeneratedSinkToken() {
  const ok = await copyText(generatedSinkToken.value)
  if (ok) {
    ElMessage.success('令牌已复制')
  } else {
    ElMessage.error('复制失败，请手动选择复制')
  }
}

async function copyText(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 非安全上下文或权限受限时降级到 execCommand
    }
  }
  return legacyCopyText(text)
}

function legacyCopyText(text: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '-9999px'
  textarea.style.left = '-9999px'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  textarea.setSelectionRange(0, text.length)
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  textarea.remove()
  return ok
}

function authSummary(result: EndpointAuthCheckResult | null): string {
  if (!result) {
    return ''
  }
  const handshake = result.handshake ? ` · 握手 ${result.handshake}` : ''
  return `${result.message}：Source ${result.sourceDisplay || '部署配置'} / Sink ${result.sinkMasked || '未配置'}${handshake}`
}
</script>

<template>
  <section class="endpoint-view" data-test="endpoint-view">
    <div class="endpoint-view__head">
      <div>
        <h2>端管理</h2>
        <p>Source 端固定为当前实例；Sink 端可注册本地与远程实例，测试命令会下发到对应端。</p>
      </div>
      <button type="button" class="endpoint-view__primary" data-test="create-endpoint" @click="openCreate">
        新增 Sink 端
      </button>
    </div>

    <section class="endpoint-group" data-test="source-endpoints">
      <h3>Source 端 · 仅当前实例</h3>
      <div class="endpoint-card-list">
        <article v-for="endpoint in sourceEndpoints()" :key="endpoint.id" class="endpoint-card">
          <div class="endpoint-card__top">
            <strong>{{ endpoint.name }}</strong>
            <span class="endpoint-card__badge">当前实例</span>
          </div>
          <dl>
            <div><dt>实例 ID</dt><dd :title="endpoint.instanceId ?? '-'">{{ endpoint.instanceId ?? '-' }}</dd></div>
            <div><dt>状态</dt><dd>{{ statusLabel(endpoint.status) }}</dd></div>
          </dl>
          <div class="endpoint-card__actions">
            <button type="button" :disabled="probing[endpoint.id]" @click="handleProbe(endpoint)">
              {{ probing[endpoint.id] ? '探活中…' : '探活' }}
            </button>
          </div>
        </article>
      </div>
    </section>

    <section class="endpoint-group" data-test="sink-endpoints">
      <h3>Sink 端 · {{ sinkEndpoints().length }}</h3>
      <div v-if="sinkEndpoints().length === 0" class="endpoint-empty">尚未注册 Sink 端</div>
      <div class="endpoint-card-list">
        <article v-for="endpoint in sinkEndpoints()" :key="endpoint.id" class="endpoint-card">
          <div class="endpoint-card__top">
            <strong>{{ endpoint.name }}</strong>
            <span
              class="endpoint-card__badge"
              :class="{
                'endpoint-card__badge--ok': endpoint.status === 'READY',
                'endpoint-card__badge--warn': endpoint.status !== 'READY',
              }"
              :data-test="`sink-status-${endpoint.id}`"
            >
              {{ statusLabel(endpoint.status) }}
            </span>
          </div>
          <dl>
            <div><dt>地址</dt><dd :title="endpoint.baseUrl ?? '-'">{{ endpoint.baseUrl ?? '-' }}</dd></div>
            <div><dt>实例 ID</dt><dd :title="endpoint.instanceId ?? '-'">{{ endpoint.instanceId ?? '-' }}</dd></div>
            <div>
              <dt>最近探活</dt>
              <dd :title="formatDateTime(endpoint.lastProbeAt)">{{ formatDateTime(endpoint.lastProbeAt) }}</dd>
            </div>
          </dl>
          <div class="endpoint-card__actions">
            <button type="button" :disabled="probing[endpoint.id]" @click="handleProbe(endpoint)">
              {{ probing[endpoint.id] ? '探活中…' : '探活' }}
            </button>
            <button type="button" @click="openEdit(endpoint)">编辑</button>
            <button
              type="button"
              :disabled="authChecking[endpoint.id]"
              @click="handleAuthCheck(endpoint)"
            >
              {{ authChecking[endpoint.id] ? '检查中…' : '检查批次认证' }}
            </button>
            <button
              v-if="!endpoint.isSelf"
              type="button"
              class="endpoint-card__danger"
              @click="handleDelete(endpoint)"
            >
              删除
            </button>
          </div>
          <p
            v-if="authResult[endpoint.id]"
            class="endpoint-card__auth"
            :class="{ 'is-error': !authResult[endpoint.id]?.ok }"
          >
            {{ authSummary(authResult[endpoint.id]) }}
          </p>
        </article>
      </div>
    </section>

    <div v-if="showForm" class="endpoint-modal" data-test="endpoint-form">
      <form class="endpoint-modal__card" @submit.prevent="handleSave">
        <h3>{{ editing ? '编辑 Sink 端' : '新增 Sink 端' }}</h3>
        <label class="endpoint-field">
          端名称
          <input
            v-model="form.name"
            type="text"
            placeholder="例如：生产 Sink-01"
            :disabled="Boolean(editing?.isSelf)"
          />
        </label>
        <label class="endpoint-field">
          访问地址
          <input
            v-model="form.baseUrl"
            type="text"
            placeholder="http://sink-host:19090/mic-data-sync"
            :disabled="Boolean(editing?.isSelf)"
          />
        </label>
        <label class="endpoint-field">
          Sink 访问令牌
          <span class="endpoint-field__row">
            <input
              v-model="form.sinkToken"
              type="password"
              :disabled="Boolean(editing?.isSelf && editing?.role !== 'SINK')"
              placeholder="目标端系统管理页轮换获取；编辑时留空保持不变"
            />
            <button
              v-if="editing?.isSelf && editing?.role === 'SINK'"
              type="button"
              class="endpoint-field__generate"
              @click="handleGenerateSinkToken"
            >
              生成本机新令牌
            </button>
          </span>
          <small v-if="!editing?.isSelf" class="endpoint-field__hint">
            多个 Sink 端令牌不同时必须填写，发送批次时按端使用。
          </small>
          <small v-else-if="editing?.role === 'SINK'" class="endpoint-field__hint">
            本机 Sink 令牌统一在端管理维护，可点击“生成本机新令牌”。
          </small>
          <div v-if="generatedSinkToken" class="endpoint-field__generated" data-test="generated-sink-token">
            <p>新令牌仅展示一次，请复制并保存：</p>
            <pre class="endpoint-field__generated-token">{{ generatedSinkToken }}</pre>
            <button type="button" data-test="copy-generated-sink-token" @click="handleCopyGeneratedSinkToken">
              复制令牌
            </button>
          </div>
        </label>
        <div class="endpoint-modal__actions">
          <button type="button" @click="showForm = false">取消</button>
          <button type="submit" class="endpoint-view__primary">保存</button>
        </div>
      </form>
    </div>
  </section>
</template>

<style scoped>
.endpoint-view {
  width: 100%;
}

.endpoint-view__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.endpoint-view__head h2 {
  margin: 0;
}

.endpoint-view__head p {
  margin: 4px 0 0;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.endpoint-view__primary {
  height: 36px;
  padding: 0 18px;
  border: none;
  border-radius: var(--mic-radius);
  background: var(--mic-primary);
  color: #fff;
  cursor: pointer;
}

.endpoint-group {
  margin-top: 22px;
}

.endpoint-group h3 {
  margin: 0 0 10px;
  color: var(--mic-text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.endpoint-card-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
  gap: 10px;
  align-items: start;
}

.endpoint-card {
  padding: 18px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
  min-width: 0;
}

.endpoint-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.endpoint-card__top strong {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.endpoint-card__badge {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--mic-neutral-soft);
  color: var(--mic-text-secondary);
  font-size: 12px;
}

.endpoint-card__badge--ok {
  background: var(--mic-success-soft, #e1f3d8);
  color: var(--mic-success, #67c23a);
}

.endpoint-card__badge--warn {
  background: #fdf1e2;
  color: #b45309;
}

.endpoint-card dl {
  margin: 14px 0 0;
  font-size: 13px;
}

.endpoint-card dl > div {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 6px 12px;
}

.endpoint-card dt {
  color: var(--mic-text-secondary);
}

.endpoint-card dd {
  margin: 0;
  font-family: var(--mic-font-mono, monospace);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.endpoint-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.endpoint-card__actions button,
.endpoint-modal__actions button {
  height: 32px;
  padding: 0 14px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
  color: var(--mic-text);
  cursor: pointer;
}

.endpoint-card__actions .endpoint-card__danger {
  color: var(--mic-danger, #f56c6c);
}

.endpoint-card__auth {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: var(--mic-radius);
  background: var(--mic-success-soft, #e1f3d8);
  color: var(--mic-success, #67c23a);
  font-size: 12px;
}

.endpoint-card__auth.is-error {
  background: #fdf0f0;
  color: var(--mic-danger, #f56c6c);
}

.endpoint-empty {
  padding: 18px;
  border: 1px dashed var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text-secondary);
  text-align: center;
}

.endpoint-modal {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 80px 16px;
  background: rgb(0 0 0 / 35%);
}

.endpoint-modal__card {
  width: min(460px, 100%);
  padding: 18px;
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.endpoint-modal__card h3 {
  margin: 0 0 14px;
}

.endpoint-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.endpoint-field input {
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text);
  background: var(--mic-surface);
}

.endpoint-field__row {
  display: flex;
  gap: 8px;
}

.endpoint-field__row input {
  flex: 1;
  min-width: 0;
}

.endpoint-field__generate {
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text);
  background: var(--mic-surface);
  cursor: pointer;
  white-space: nowrap;
}

.endpoint-field__hint {
  color: var(--mic-text-secondary);
  font-size: 12px;
}

.endpoint-field__generated {
  margin-top: 8px;
  padding: 10px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface-2, #f7faf8);
  font-size: 12px;
}

.endpoint-field__generated p {
  margin: 0 0 8px;
  color: var(--mic-danger, #f56c6c);
}

.endpoint-field__generated-token {
  margin: 0 0 8px;
  padding: 8px;
  overflow-x: auto;
  border-radius: 4px;
  background: var(--mic-surface);
  font-family: var(--mic-font-mono, monospace);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.endpoint-field__generated button {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
  color: var(--mic-text);
  cursor: pointer;
}

.endpoint-modal__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
</style>
