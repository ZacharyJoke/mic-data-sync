import http from '@/api/http'

/** 端角色（v1：Source 固定为当前实例，Sink 可多个） */
export type EndpointRole = 'SOURCE' | 'SINK'

/** 端注册项（不含管理令牌） */
export interface EndpointItem {
  id: string
  name: string
  role: EndpointRole
  baseUrl: string | null
  instanceId: string | null
  isSelf: boolean
  status: string
  lastProbeAt: string | null
  createdAt: string
  updatedAt: string
}

/** 创建/更新请求（令牌为空表示保留原值） */
export interface EndpointRequest {
  name: string
  baseUrl: string
  sinkToken?: string
}

/** 探活结果 */
export interface ProbeResult {
  message: string
  endpoint: EndpointItem
}

/** 批次认证检查结果 */
export interface EndpointAuthCheckResult {
  ok: boolean
  message: string
  sourceDisplay: string
  sinkMasked: string
  handshake: string | null
}

/** Sink 令牌状态（掩码展示） */
export interface EndpointSinkToken {
  configured: boolean
  display: string
}

/** Sink 端完整状态（握手信息） */
export interface EndpointStatus {
  sinkInstanceId: string
  startupId: string
  databaseType: string | null
  capabilityStatus: string
  message: string | null
  dbaSql: string | null
  protocolVersion: number
  batchLimits: { maxRowsPerBatch: number; maxPayloadBytes: number }
  tlsInsecureSkipVerify: boolean
}

/** 列出端（可按角色过滤）。 */
export async function listEndpoints(role?: EndpointRole): Promise<EndpointItem[]> {
  const response = await http.get<EndpointItem[]>('/endpoints', {
    params: role ? { role } : {},
  })
  return response.data
}

/** 端详情。 */
export async function getEndpoint(id: string): Promise<EndpointItem> {
  const response = await http.get<EndpointItem>(`/endpoints/${id}`)
  return response.data
}

/** 新增 Sink 端。 */
export async function createEndpoint(request: EndpointRequest): Promise<EndpointItem> {
  const response = await http.post<EndpointItem>('/endpoints', request)
  return response.data
}

/** 更新 Sink 端。 */
export async function updateEndpoint(id: string, request: EndpointRequest): Promise<EndpointItem> {
  const response = await http.put<EndpointItem>(`/endpoints/${id}`, request)
  return response.data
}

/** 删除 Sink 端。 */
export async function deleteEndpoint(id: string): Promise<void> {
  await http.delete(`/endpoints/${id}`)
}

/** 探活并回填实例 ID / 状态。 */
export async function probeEndpoint(id: string): Promise<ProbeResult> {
  const response = await http.post<ProbeResult>(`/endpoints/${id}/probe`)
  return response.data
}

/** 检查该 Sink 端的批次认证（Source 令牌与 Sink 令牌比对）。 */
export async function checkEndpointAuth(id: string): Promise<EndpointAuthCheckResult> {
  const response = await http.post<EndpointAuthCheckResult>(`/endpoints/${id}/auth-check`)
  return response.data
}

/** 查询 Sink 端令牌掩码状态。 */
export async function getEndpointSinkToken(id: string): Promise<EndpointSinkToken> {
  const response = await http.get<EndpointSinkToken>(`/endpoints/${id}/sink-token`)
  return response.data
}

/** 查询 Sink 端完整状态（数据库类型/就绪/批次限制等）。 */
export async function getEndpointStatus(id: string): Promise<EndpointStatus> {
  const response = await http.get<EndpointStatus>(`/endpoints/${id}/status`)
  return response.data
}
