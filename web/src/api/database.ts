import http from '@/api/http'

/** 数据源角色 */
export type DatabaseRole = 'SOURCE' | 'SINK'

/** 支持的数据库类型 */
export type DatabaseProduct = 'KINGBASE_ES' | 'OPEN_GAUSS'

/** 数据源档案（脱敏，不含密码） */
export interface DataSourceItem {
  id: string
  endpointId: string
  name: string
  role: DatabaseRole
  product: DatabaseProduct
  jdbcUrl: string
  username: string
  driverType: string
  createdAt: string | null
  updatedAt: string | null
}

/** 创建/更新请求体（password 为空表示保留原值） */
export interface DataSourceRequest {
  endpointId: string
  name: string
  product: DatabaseProduct
  jdbcUrl: string
  username: string
  password?: string
  driverType: string
}

/** 测试请求体（密码为空时由所属端回退已保存密码） */
export interface TestDataSourceRequest {
  id?: string
  endpointId: string
  name?: string
  product: DatabaseProduct
  jdbcUrl: string
  username: string
  password?: string
  driverType: string
}

/** 连接测试结果（不包含密码） */
export interface ConnectionTestResult {
  ok: boolean
  productName: string | null
  productVersion: string | null
  selectCapable: boolean | null
  transactionCapable: boolean | null
  currentUser: string | null
  errorCode: string | null
  message: string | null
}

/** 列出数据源（可按所属端过滤；不含密码）。 */
export async function listDataSources(endpointId?: string): Promise<DataSourceItem[]> {
  const response = await http.get<DataSourceItem[]>('/data-sources', {
    params: endpointId ? { endpointId } : {},
  })
  return response.data
}

/** 创建数据源档案（远程端由控制台下发给所属端）。 */
export async function createDataSource(request: DataSourceRequest): Promise<DataSourceItem> {
  const response = await http.post<DataSourceItem>('/data-sources', request)
  return response.data
}

/** 更新数据源档案。 */
export async function updateDataSource(
  id: string,
  request: DataSourceRequest,
): Promise<DataSourceItem> {
  const response = await http.put<DataSourceItem>(`/data-sources/${id}`, request)
  return response.data
}

/** 删除数据源档案。 */
export async function deleteDataSource(id: string): Promise<void> {
  await http.delete(`/data-sources/${id}`)
}

/** 测试连接：下发到所属端执行。 */
export async function testDataSource(request: TestDataSourceRequest): Promise<ConnectionTestResult> {
  const response = await http.post<ConnectionTestResult>('/data-sources/test', request)
  return response.data
}
