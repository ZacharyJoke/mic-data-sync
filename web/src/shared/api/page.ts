export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface CommandResult {
  accepted: boolean
  resourceId: string
  status: string
  message: string
}
