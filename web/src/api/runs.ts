import http from '@/api/http'
import type { CommandResult, PageResult } from '@/shared/api/page'
import type { AxiosError } from 'axios'

export interface RunItem {
  runId: string
  taskId: string
  taskName: string
  kind: string
  status: string
  pauseReason: string | null
  startedAt: string
  endedAt: string | null
  sourceRowCount: number
  confirmedRowCount: number
}

export interface RunDiagnosis {
  runId: string
  stage: string
  code: string
  summary: string
  impact: string
  retryable: boolean
  requestId: string
  suggestedActions: Array<{ type: string; label: string }>
}

export interface RunAction {
  type: string
  enabled: boolean
  reason: string
}

export interface RunActions {
  runId: string
  actions: RunAction[]
}

export interface BatchItem {
  batchId: string
  runId: string
  batchSequence: number
  payloadHash: string
  rowCount: number
  timeWatermark: string | null
  status: string
  attemptCount: number
  createdAt: string
}

export interface RunFilters {
  status?: string
  taskId?: string
  kind?: string
  startedFrom?: string
  startedTo?: string
  keyword?: string
}

export async function listRuns(
  page = 1,
  size = 20,
  filters: RunFilters = {},
): Promise<PageResult<RunItem>> {
  const response = await http.get<PageResult<RunItem>>('/runs', {
    params: { page, size, ...filters },
  })
  return response.data
}

export async function listTaskRuns(
  taskId: string,
  page = 1,
  size = 20,
  filters: RunFilters = {},
): Promise<PageResult<RunItem>> {
  const response = await http.get<PageResult<RunItem>>(`/tasks/${taskId}/runs`, {
    params: { page, size, ...filters },
  })
  return response.data
}

export async function getRun(runId: string): Promise<RunItem> {
  const response = await http.get<RunItem>(`/runs/${runId}`)
  return response.data
}

export async function getRunDiagnosis(runId: string): Promise<RunDiagnosis | null> {
  try {
    const response = await http.get<RunDiagnosis>(`/runs/${runId}/diagnosis`)
    return response.data
  } catch (error) {
    if ((error as AxiosError).response?.status === 404) {
      return null
    }
    throw error
  }
}

export async function getRunActions(runId: string): Promise<RunActions> {
  const response = await http.get<RunActions>(`/runs/${runId}/actions`)
  return response.data
}

export async function startFullSync(taskId: string): Promise<CommandResult> {
  const response = await http.post<CommandResult>(`/tasks/${taskId}/runs/full`)
  return response.data
}

export async function startIncremental(taskId: string): Promise<CommandResult> {
  const response = await http.post<CommandResult>(`/tasks/${taskId}/runs/incremental`)
  return response.data
}

export async function pauseRun(runId: string): Promise<CommandResult> {
  const response = await http.post<CommandResult>(`/runs/${runId}/pause`)
  return response.data
}

export async function resumeRun(runId: string): Promise<CommandResult> {
  const response = await http.post<CommandResult>(`/runs/${runId}/resume`)
  return response.data
}

export async function retryRun(runId: string, idempotencyKey: string): Promise<CommandResult> {
  const response = await http.post<CommandResult>(
    `/runs/${runId}/retry`,
    undefined,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
  return response.data
}

export async function listRunBatches(
  runId: string,
  page = 1,
  size = 20,
): Promise<PageResult<BatchItem>> {
  const response = await http.get<PageResult<BatchItem>>(`/runs/${runId}/batches`, {
    params: { page, size },
  })
  return response.data
}
