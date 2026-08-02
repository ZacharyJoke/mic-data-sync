<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  createDataSource,
  deleteDataSource,
  listDataSources,
  testDataSource,
  updateDataSource,
  type ConnectionTestResult,
  type DataSourceItem,
  type DataSourceRequest,
  type DatabaseProduct,
} from '@/api/database'
import { listEndpoints, type EndpointItem } from '@/api/endpoints'

interface FormState {
  name: string
  product: DatabaseProduct | ''
  jdbcUrl: string
  username: string
  password: string
  driverType: string
}

const emptyForm = (): FormState => ({
  name: '',
  product: '',
  jdbcUrl: '',
  username: '',
  password: '',
  driverType: '',
})

const PRODUCT_OPTIONS: { label: string; value: DatabaseProduct; driverType: string }[] = [
  { label: '人大金仓 KingbaseES', value: 'KINGBASE_ES', driverType: 'kingbase8' },
  { label: 'openGauss', value: 'OPEN_GAUSS', driverType: 'opengauss' },
]

const endpoints = ref<EndpointItem[]>([])
const selectedEndpointId = ref('')
const dataSources = ref<DataSourceItem[]>([])
const loading = ref(false)
const testing = ref<Record<string, boolean>>({})
const testResult = ref<Record<string, ConnectionTestResult | null>>({})
const editing = ref<DataSourceItem | null>(null)
const showForm = ref(false)
const form = ref<FormState>(emptyForm())

const selectedEndpoint = computed(() =>
  endpoints.value.find((endpoint) => endpoint.id === selectedEndpointId.value),
)

onMounted(async () => {
  try {
    endpoints.value = await listEndpoints()
    const selfSource = endpoints.value.find((endpoint) => endpoint.isSelf && endpoint.role === 'SOURCE')
    selectedEndpointId.value = selfSource?.id ?? endpoints.value[0]?.id ?? ''
    if (selectedEndpointId.value) {
      await loadDataSources()
    }
  } catch {
    ElMessage.error('数据加载失败')
  }
})

async function loadDataSources() {
  if (!selectedEndpointId.value) {
    dataSources.value = []
    return
  }
  loading.value = true
  try {
    dataSources.value = await listDataSources(selectedEndpointId.value)
  } catch {
    ElMessage.error('数据源列表加载失败，请确认所属端可访问')
    dataSources.value = []
  } finally {
    loading.value = false
  }
}

function endpointLabel(endpoint: EndpointItem): string {
  const role = endpoint.role === 'SOURCE' ? 'Source' : 'Sink'
  return `${endpoint.name}（${role}）`
}

function applyProduct(value: DatabaseProduct | '') {
  form.value.product = value
  const option = PRODUCT_OPTIONS.find((item) => item.value === value)
  form.value.driverType = option ? option.driverType : ''
}

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  showForm.value = true
}

function openEdit(item: DataSourceItem) {
  editing.value = item
  form.value = {
    name: item.name,
    product: item.product,
    jdbcUrl: item.jdbcUrl,
    username: item.username,
    password: '',
    driverType: item.driverType,
  }
  showForm.value = true
}

function buildRequest(): DataSourceRequest {
  return {
    endpointId: selectedEndpointId.value,
    name: form.value.name.trim(),
    product: form.value.product as DatabaseProduct,
    jdbcUrl: form.value.jdbcUrl.trim(),
    username: form.value.username.trim(),
    password: form.value.password || undefined,
    driverType: form.value.driverType,
  }
}

function validate(): string | null {
  if (!form.value.name.trim()) {
    return '请填写数据源名称'
  }
  if (!form.value.product) {
    return '请选择数据库类型'
  }
  if (!form.value.jdbcUrl.trim()) {
    return '请输入 JDBC URL'
  }
  if (!form.value.username.trim()) {
    return '请输入用户名'
  }
  if (!editing.value && !form.value.password) {
    return '首次保存必须填写密码'
  }
  return null
}

async function handleSave() {
  const error = validate()
  if (error) {
    ElMessage.warning(error)
    return
  }
  try {
    if (editing.value) {
      await updateDataSource(editing.value.id, buildRequest())
      ElMessage.success('数据源已更新')
    } else {
      await createDataSource(buildRequest())
      ElMessage.success('数据源已创建')
    }
    showForm.value = false
    await loadDataSources()
  } catch {
    ElMessage.error('保存失败（远程端不可达或名称重复）')
  }
}

async function handleDelete(item: DataSourceItem) {
  try {
    await ElMessageBox.confirm(`确认删除数据源「${item.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteDataSource(item.id)
    ElMessage.success('数据源已删除')
    await loadDataSources()
  } catch {
    ElMessage.error('删除失败（数据源可能已被任务引用）')
  }
}

async function handleTest(item: DataSourceItem) {
  testing.value[item.id] = true
  testResult.value[item.id] = null
  try {
    const result = await testDataSource({
      id: item.id,
      endpointId: item.endpointId,
      name: item.name,
      product: item.product,
      jdbcUrl: item.jdbcUrl,
      username: item.username,
      password: '',
      driverType: item.driverType,
    })
    testResult.value[item.id] = result
  } catch {
    testResult.value[item.id] = {
      ok: false,
      productName: null,
      productVersion: null,
      selectCapable: null,
      transactionCapable: null,
      currentUser: null,
      errorCode: 'REQUEST_FAILED',
      message: '测试请求失败',
    }
  } finally {
    testing.value[item.id] = false
  }
}

async function handleFormTest() {
  const error = validate()
  if (error) {
    ElMessage.warning(error)
    return
  }
  try {
    const request = buildRequest()
    const result = await testDataSource({
      endpointId: request.endpointId,
      name: request.name,
      product: request.product,
      jdbcUrl: request.jdbcUrl,
      username: request.username,
      password: request.password,
      driverType: request.driverType,
      id: editing.value?.id,
    })
    if (result.ok) {
      ElMessage.success('连接成功')
    } else {
      ElMessage.error(result.message ?? '连接失败')
    }
  } catch {
    ElMessage.error('测试请求失败')
  }
}
</script>

<template>
  <section class="data-source-view" data-test="data-source-view">
    <div class="data-source-view__head">
      <div>
        <h2>数据源管理</h2>
        <p>每个数据源归属于一个 Source 端或 Sink 端；测试连接由控制台下发给所属端执行。</p>
      </div>
      <button type="button" class="data-source-view__primary" data-test="create-data-source" @click="openCreate">
        新增数据源
      </button>
    </div>

    <label class="data-source-view__endpoint">
      所属端
      <select v-model="selectedEndpointId" data-test="endpoint-select" @change="loadDataSources">
        <option v-for="endpoint in endpoints" :key="endpoint.id" :value="endpoint.id">
          {{ endpointLabel(endpoint) }}（{{ endpoint.status }}）
        </option>
      </select>
    </label>

    <div class="data-source-table">
      <table data-test="data-source-table">
        <thead>
          <tr>
            <th>数据源名称</th>
            <th>类型</th>
            <th>JDBC / 库</th>
            <th>用户</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="dataSources.length === 0">
            <td colspan="5" class="data-source-table__empty">
              {{ loading ? '加载中…' : '该端下还没有数据源' }}
            </td>
          </tr>
          <tr v-for="item in dataSources" :key="item.id" data-test="data-source-row">
            <td>{{ item.name }}</td>
            <td>{{ item.product }}</td>
            <td class="data-source-table__mono" :title="item.jdbcUrl">{{ item.jdbcUrl }}</td>
            <td class="data-source-table__mono" :title="item.username">{{ item.username }}</td>
            <td>
              <div class="data-source-table__actions">
                <button type="button" :disabled="testing[item.id]" @click="handleTest(item)">
                  {{ testing[item.id] ? '测试中…' : '测试连接' }}
                </button>
                <button type="button" @click="openEdit(item)">编辑</button>
                <button type="button" class="data-source-table__danger" @click="handleDelete(item)">删除</button>
              </div>
              <div v-if="testResult[item.id]" class="data-source-table__result" :class="{ 'is-error': !testResult[item.id]?.ok }">
                {{ testResult[item.id]?.ok ? '连接成功' : testResult[item.id]?.message }}
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="showForm" class="data-source-modal" data-test="data-source-form">
      <form class="data-source-modal__card" @submit.prevent="handleSave">
        <h3>{{ editing ? '编辑数据源' : '新增数据源' }}</h3>
        <label class="data-source-field">
          所属端
          <input type="text" :value="selectedEndpoint ? endpointLabel(selectedEndpoint) : ''" disabled />
        </label>
        <label class="data-source-field">
          数据源名称
          <input v-model="form.name" type="text" placeholder="例如：生产库 A" />
        </label>
        <label class="data-source-field">
          数据库类型
          <select v-model="form.product" @change="applyProduct(form.product)">
            <option value="" disabled>请选择</option>
            <option v-for="option in PRODUCT_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </label>
        <label class="data-source-field">
          JDBC URL
          <input v-model="form.jdbcUrl" type="text" placeholder="jdbc:opengauss://host:15432/db" />
        </label>
        <label class="data-source-field">
          用户名
          <input v-model="form.username" type="text" autocomplete="username" />
        </label>
        <label class="data-source-field">
          密码
          <input
            v-model="form.password"
            type="password"
            autocomplete="new-password"
            :placeholder="editing ? '已保存（留空保持不变）' : '请输入密码'"
          />
        </label>
        <div class="data-source-modal__actions">
          <button type="button" @click="showForm = false">取消</button>
          <button type="button" @click="handleFormTest">测试连接</button>
          <button type="submit" class="data-source-view__primary">保存</button>
        </div>
      </form>
    </div>
  </section>
</template>

<style scoped>
.data-source-view {
  width: 100%;
}

.data-source-view__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.data-source-view__head h2 {
  margin: 0;
}

.data-source-view__head p {
  margin: 4px 0 0;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.data-source-view__primary {
  height: 36px;
  padding: 0 18px;
  border: none;
  border-radius: var(--mic-radius);
  background: var(--mic-primary);
  color: #fff;
  cursor: pointer;
}

.data-source-view__endpoint {
  display: flex;
  max-width: 420px;
  flex-direction: column;
  gap: 6px;
  margin-top: 16px;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.data-source-view__endpoint select,
.data-source-field select,
.data-source-field input {
  height: 36px;
  padding: 0 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  color: var(--mic-text);
  background: var(--mic-surface);
}

.data-source-table {
  width: 100%;
  margin-top: 14px;
  overflow-x: auto;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.data-source-table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.data-source-table th,
.data-source-table td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--mic-border);
  text-align: left;
}

.data-source-table th {
  color: var(--mic-text-secondary);
  background: var(--mic-neutral-soft, #f5f7fa);
  font-size: 12px;
}

.data-source-table tr:last-child td {
  border-bottom: none;
}

.data-source-table__mono {
  font-family: var(--mic-font-mono, monospace);
  font-size: 12px;
  white-space: normal;
  word-break: break-all;
}

.data-source-table__empty {
  padding: 24px;
  color: var(--mic-text-secondary);
  text-align: center;
}

.data-source-table__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.data-source-table__actions button,
.data-source-modal__actions button {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--mic-border);
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
  color: var(--mic-text);
  cursor: pointer;
}

.data-source-table__actions .data-source-table__danger {
  color: var(--mic-danger, #f56c6c);
}

.data-source-table__result {
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: var(--mic-radius);
  background: var(--mic-success-soft, #e1f3d8);
  color: var(--mic-success, #67c23a);
  font-size: 12px;
}

.data-source-table__result.is-error {
  background: #fdf0f0;
  color: var(--mic-danger, #f56c6c);
}

.data-source-modal {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 60px 16px;
  background: rgb(0 0 0 / 35%);
}

.data-source-modal__card {
  width: min(520px, 100%);
  padding: 18px;
  border-radius: var(--mic-radius);
  background: var(--mic-surface);
}

.data-source-modal__card h3 {
  margin: 0 0 14px;
}

.data-source-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
  color: var(--mic-text-secondary);
  font-size: 13px;
}

.data-source-modal__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
</style>
