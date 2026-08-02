import http from '@/api/http'

/** 源表字段元数据 */
export interface ColumnInfo {
  name: string
  typeName: string
  size: number
  nullable: boolean
  primaryKey: boolean
}

/** 源表元数据与分页键建议 */
export interface TableMetadataInfo {
  schema: string
  table: string
  columns: ColumnInfo[]
  primaryKeyColumns: string[]
  uniqueIndexes: string[][]
  paginationKeySuggestions: string[][]
}

/** 测试查询响应 */
export interface SampleResult {
  columns: string[]
  rows: unknown[][]
}

/** 列出 Source 数据库可用 Schema。 */
export async function listSchemas(dataSourceId?: string): Promise<string[]> {
  const response = await http.get<{ schemas: string[] }>('/source/metadata/schemas', {
    params: dataSourceId ? { dataSourceId } : {},
  })
  return response.data.schemas ?? []
}

/** 列出指定 Schema 下的表。 */
export async function listTables(schema: string, dataSourceId?: string): Promise<string[]> {
  const response = await http.get<{ tables: string[] }>(
    `/source/metadata/schemas/${encodeURIComponent(schema)}/tables`,
    { params: dataSourceId ? { dataSourceId } : {} },
  )
  return response.data.tables ?? []
}

/** 读取表元数据（含分页键建议）。 */
export async function getTableMetadata(
  schema: string,
  table: string,
  dataSourceId?: string,
): Promise<TableMetadataInfo> {
  const response = await http.get<TableMetadataInfo>(
    `/source/metadata/schemas/${encodeURIComponent(schema)}/tables/${encodeURIComponent(table)}`,
    { params: dataSourceId ? { dataSourceId } : {} },
  )
  return response.data
}

/** 测试查询（最多 20 行）。 */
export async function sampleRows(
  schema: string,
  table: string,
  columns: string[],
  dataSourceId?: string,
): Promise<SampleResult> {
  const response = await http.post<SampleResult>(
    `/source/metadata/schemas/${encodeURIComponent(schema)}/tables/${encodeURIComponent(table)}/sample`,
    { columns },
    { params: dataSourceId ? { dataSourceId } : {} },
  )
  return response.data
}


/** SQL 探查结果字段 */
export interface ResultColumnInfo {
  name: string
  typeName: string
  logicalType: string
  nullable: boolean
}

/** SQL→Table 转换结果 */
export interface TableConversion {
  success: boolean
  schema: string | null
  table: string | null
  selectedColumns: string[] | null
  filters: unknown[] | null
  paginationKeys: string[] | null
}

/** SQL 探查响应 */
export interface SqlInspectionResult {
  valid: boolean
  errorCode: string | null
  message: string | null
  resultColumns: ResultColumnInfo[] | null
  duplicateNames: string[] | null
  structureFingerprint: string | null
  tableConversion: TableConversion | null
}

/** 校验并探查单表 SQL（返回字段、指纹与 SQL→Table 转换建议）。 */
export async function inspectSql(sql: string, dataSourceId?: string): Promise<SqlInspectionResult> {
  const response = await http.post<SqlInspectionResult>('/source/sql/inspect', { sql }, {
    params: dataSourceId ? { dataSourceId } : {},
  })
  return response.data
}
