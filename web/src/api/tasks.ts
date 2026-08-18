import http from '@/api/http'
import type { PageResult } from '@/shared/api/page'

export interface FieldMapping {
  sourceField: string
  targetField: string
}

export interface TaskFilters {
  keyword?: string
  lifecycleStatus?: string
  readMode?: string
  latestRunStatus?: string
}

export interface LatestRunSummary {
  runId: string
  kind: string
  status: string
  startedAt: string
  endedAt: string | null
}

export interface TaskItem {
  taskId: string
  name: string
  version: number
  lifecycleStatus: string
  readMode: string
  readDefinition: Record<string, unknown> | null
  targetSchema: string | null
  targetTable: string
  writeMode: string
  uniqueKeys: string[]
  fieldMappings: FieldMapping[]
  remoteSinkUrl: string | null
  expectedSinkInstanceId: string | null
  sourceEndpointId: string | null
  sinkEndpointId: string | null
  sourceDataSourceId: string | null
  targetDataSourceId: string | null
  createdAt: string
  updatedAt: string
  latestRun: LatestRunSummary | null
}

export interface CreateTaskRequest {
  name: string
  readMode: 'TABLE' | 'SQL'
  readDefinition: Record<string, unknown>
  targetSchema?: string
  targetTable: string
  writeMode: 'UPSERT' | 'UPSERT_NO_OVERWRITE' | 'INSERT_ONLY' | 'REPLACE_ALL'
  uniqueKeys: string[]
  fieldMappings: FieldMapping[]
  remoteSinkUrl?: string
  expectedSinkInstanceId?: string
  sourceEndpointId?: string
  sinkEndpointId?: string
  sourceDataSourceId?: string
  targetDataSourceId?: string
}

export type ValidationSeverity = 'BLOCKING' | 'WARNING'

export type ValidationStage =
  | 'SOURCE_CONFIGURATION'
  | 'SOURCE_VALIDATION'
  | 'TARGET_CONFIGURATION'
  | 'TARGET_VALIDATION'
  | 'SINK_HANDSHAKE'

export interface ValidationIssue {
  severity: ValidationSeverity
  code: string
  message: string
  field: string
  stage: ValidationStage
  suggestedAction: string
}

export interface ValidationReport {
  valid: boolean
  issues: ValidationIssue[]
}

/** 目标表字段 */
export interface TargetColumn {
  name: string
  typeName: string
  nullable: boolean
  primaryKey: boolean
}

/** 目标表元数据 */
export interface TargetMetadata {
  schema: string | null
  table: string
  columns: TargetColumn[]
  primaryKeyColumns: string[]
  uniqueIndexes: string[][]
}

export async function listTasks(
  page = 1,
  size = 20,
  filters: TaskFilters = {},
): Promise<PageResult<TaskItem>> {
  const response = await http.get<PageResult<TaskItem>>('/tasks', {
    params: { page, size, ...filters },
  })
  return response.data
}

export async function getTask(taskId: string): Promise<TaskItem> {
  const response = await http.get<TaskItem>(`/tasks/${taskId}`)
  return response.data
}

export async function createTask(request: CreateTaskRequest): Promise<TaskItem> {
  const response = await http.post<TaskItem>('/tasks', request)
  return response.data
}

export async function updateTask(
  taskId: string,
  request: CreateTaskRequest,
): Promise<TaskItem> {
  const response = await http.put<TaskItem>(`/tasks/${taskId}`, request)
  return response.data
}

export async function deleteTask(taskId: string): Promise<void> {
  await http.delete(`/tasks/${taskId}`)
}

export async function validateTask(taskId: string): Promise<ValidationReport> {
  const response = await http.post<ValidationReport>(`/tasks/${taskId}/validate`)
  return response.data
}

export async function enableTask(taskId: string): Promise<TaskItem> {
  const response = await http.post<TaskItem>(`/tasks/${taskId}/enable`)
  return response.data
}

export async function pauseTask(taskId: string): Promise<TaskItem> {
  const response = await http.post<TaskItem>(`/tasks/${taskId}/pause`)
  return response.data
}

export async function resumeTask(taskId: string): Promise<TaskItem> {
  const response = await http.post<TaskItem>(`/tasks/${taskId}/resume`)
  return response.data
}

export async function disableTask(taskId: string): Promise<TaskItem> {
  const response = await http.post<TaskItem>(`/tasks/${taskId}/disable`)
  return response.data
}

export async function preflightTask(request: CreateTaskRequest): Promise<ValidationReport> {
  const response = await http.post<ValidationReport>('/tasks/preflight', request)
  return response.data
}

export async function getTargetMetadata(
  schema: string | null,
  table: string,
  dataSourceId?: string,
): Promise<TargetMetadata> {
  const schemaPath = schema && schema.length > 0 ? encodeURIComponent(schema) : 'public'
  const response = await http.get<TargetMetadata>(
    `/target/metadata/${schemaPath}/${encodeURIComponent(table)}`,
    { params: dataSourceId ? { dataSourceId } : {} },
  )
  return response.data
}

/** 列出 Sink 目标库可用 Schema。 */
export async function listTargetSchemas(dataSourceId?: string): Promise<string[]> {
  const response = await http.get<{ schemas: string[] }>('/target/metadata/schemas', {
    params: dataSourceId ? { dataSourceId } : {},
  })
  return response.data.schemas ?? []
}

/** 列出指定 Schema 下的目标表。 */
export async function listTargetTables(
  schema: string,
  dataSourceId?: string,
): Promise<string[]> {
  const response = await http.get<{ tables: string[] }>(
    `/target/metadata/schemas/${encodeURIComponent(schema)}/tables`,
    { params: dataSourceId ? { dataSourceId } : {} },
  )
  return response.data.tables ?? []
}
