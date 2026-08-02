import http from '@/api/http'

/** Sink 状态（握手信息） */
export interface SinkStatus {
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

export interface SinkTokenInfo {
  configured: boolean
  display: string | null
}


export interface SourceSinkTokenInfo {
  configured: boolean
  source: 'DB' | 'CONFIG'
  display: string
}

export interface SinkAuthCheckResult {
  ok: boolean
  sourceFromDb: boolean
  sourceDisplay: string
  sinkMasked: string
  handshake: string | null
}

/** 获取 Sink 状态。 */
export async function getSinkStatus(): Promise<SinkStatus> {
  const response = await http.get<SinkStatus>('/sink/status')
  return response.data
}

/** 获取 Sink Token 掩码。 */
export async function getSinkToken(): Promise<SinkTokenInfo> {
  const response = await http.get<SinkTokenInfo>('/sink/token')
  return response.data
}

/** 轮换 Sink Token。 */
export async function rotateSinkToken(): Promise<SinkTokenInfo & { generated: string }> {
  const response = await http.post<SinkTokenInfo & { generated: string }>('/sink/token/rotate')
  return response.data
}


/** 获取 Source 端访问令牌状态（掩码）。 */
export async function getSourceSinkToken(): Promise<SourceSinkTokenInfo> {
  const response = await http.get<SourceSinkTokenInfo>('/sink/source-token')
  return response.data
}

/** 保存 Source 端访问令牌（立即生效，无需重启）。 */
export async function saveSourceSinkToken(token: string): Promise<SourceSinkTokenInfo> {
  const response = await http.put<SourceSinkTokenInfo>('/sink/source-token', { token })
  return response.data
}

/** 清除 Source 端访问令牌，回退到部署配置。 */
export async function clearSourceSinkToken(): Promise<void> {
  await http.delete('/sink/source-token')
}

/** 检查 Source 端与 Sink 端的批次认证。 */
export async function checkSinkAuth(sinkUrl?: string): Promise<SinkAuthCheckResult> {
  const response = await http.post<SinkAuthCheckResult>(
    '/sink/auth-check',
    sinkUrl ? { sinkUrl } : {},
  )
  return response.data
}
