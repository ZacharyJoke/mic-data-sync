import http from '@/api/http'
import type { RunItem } from '@/api/runs'

export interface ConnectionSummary {
  configured: boolean
  product: string | null
  healthy: boolean
  message: string
}

export interface InstanceSummary {
  instanceId: string
  version: string
  roles: string
  readiness: string
}

export interface FailureAlert {
  runId: string
  taskId: string
  taskName: string
  stage: string
  summary: string
  occurredAt: string
  severity: string
}

export interface DashboardSummary {
  source: ConnectionSummary
  sink: ConnectionSummary
  instance: InstanceSummary
  enabledTaskCount: number
  activeRunCount: number
  todaySuccessRate: number | null
  unresolvedFailureCount: number
  statisticsFrom: string
  statisticsTo: string
  recentRuns: RunItem[]
  alerts: FailureAlert[]
}

export async function getDashboardSummary(): Promise<DashboardSummary> {
  const response = await http.get<DashboardSummary>('/dashboard/summary')
  return response.data
}
