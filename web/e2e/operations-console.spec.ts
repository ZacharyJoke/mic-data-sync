import { expect, test, type Page } from '@playwright/test'

async function loginWithMockApi(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem(
      'mic-data-sync.session',
      JSON.stringify({ username: 'admin', displayName: '管理员' }),
    )
  })

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (method === 'POST' && path === '/api/v1/runs/failed-run-id/retry') {
      return route.fulfill({
        status: 202,
        contentType: 'application/json',
        body: JSON.stringify({
          accepted: true,
          resourceId: 'new-run-id',
          status: 'RUNNING',
          message: '安全重试已开始',
        }),
      })
    }

    const fixtures: Record<string, unknown> = {
      '/api/v1/dashboard/summary': {
        source: { configured: true, product: 'OPEN_GAUSS', healthy: true, message: '连接正常' },
        sink: { configured: true, product: 'OPEN_GAUSS', healthy: true, message: 'Sink 就绪' },
        instance: {
          instanceId: 'instance-1',
          version: '0.1.0-SNAPSHOT',
          roles: 'source,sink',
          readiness: 'READY',
        },
        enabledTaskCount: 3,
        activeRunCount: 1,
        todaySuccessRate: 0.5,
        unresolvedFailureCount: 1,
        statisticsFrom: '2026-08-01T00:00:00+08:00',
        statisticsTo: '2026-08-02T00:00:00+08:00',
        recentRuns: [],
        alerts: [
          {
            runId: 'failed-run-id',
            taskId: 'task-1',
            taskName: 'patient-sync',
            stage: 'TARGET_WRITE',
            summary: '目标字段类型不兼容',
            occurredAt: '2026-08-01T10:05:00Z',
            severity: 'ERROR',
          },
        ],
      },
      '/api/v1/runs/failed-run-id': {
        runId: 'failed-run-id',
        taskId: 'task-1',
        taskName: 'patient-sync',
        kind: 'INCREMENTAL',
        status: 'FAILED',
        pauseReason: '目标字段类型不兼容',
        startedAt: '2026-08-01T10:00:00Z',
        endedAt: '2026-08-01T10:05:00Z',
        sourceRowCount: 120,
        confirmedRowCount: 0,
      },
      '/api/v1/runs/failed-run-id/diagnosis': {
        runId: 'failed-run-id',
        stage: 'TARGET_WRITE',
        code: 'TARGET_COLUMN_TYPE_MISMATCH',
        summary: '目标字段类型不兼容',
        impact: '当前批次未确认，后续批次未执行',
        retryable: true,
        requestId: 'req-123',
        suggestedActions: [
          { type: 'OPEN_TASK_CONFIG', label: '检查字段映射' },
          { type: 'REVALIDATE', label: '重新校验' },
        ],
      },
      '/api/v1/runs/failed-run-id/actions': {
        runId: 'failed-run-id',
        actions: [
          { type: 'REVALIDATE', enabled: true, reason: '' },
          { type: 'RETRY', enabled: true, reason: '' },
          { type: 'RESUME', enabled: false, reason: '仅已暂停的 Run 可继续' },
        ],
      },
      '/api/v1/runs/failed-run-id/batches': {
        items: [],
        total: 0,
        page: 1,
        size: 20,
      },
      '/api/v1/runs/new-run-id': {
        runId: 'new-run-id',
        taskId: 'task-1',
        taskName: 'patient-sync',
        kind: 'INCREMENTAL',
        status: 'RUNNING',
        pauseReason: null,
        startedAt: '2026-08-01T10:06:00Z',
        endedAt: null,
        sourceRowCount: 0,
        confirmedRowCount: 0,
      },
      '/api/v1/runs/new-run-id/diagnosis': null,
      '/api/v1/runs/new-run-id/actions': {
        runId: 'new-run-id',
        actions: [{ type: 'PAUSE', enabled: true, reason: '' }],
      },
      '/api/v1/runs/new-run-id/batches': {
        items: [],
        total: 0,
        page: 1,
        size: 20,
      },
      '/api/v1/tasks': {
        items: [],
        total: 0,
        page: 1,
        size: 20,
      },
    }

    const body = fixtures[path]
    if (body !== undefined) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(body),
      })
    }
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' })
  })
}

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  )
  expect(overflow).toBe(false)
}

test('工作台到失败诊断和安全重试', async ({ page }) => {
  await loginWithMockApi(page)
  await page.goto('/')
  await page.getByRole('link', { name: '查看诊断' }).click()
  await expect(page.getByText('目标字段类型不兼容')).toBeVisible()
  await page.getByRole('button', { name: '安全重试' }).click()
  await expect(page).toHaveURL(/\/runs\/new-run-id/)
  await expectNoHorizontalOverflow(page)
})

test('手机端隐藏任务创建并保留诊断动作', async ({ page }) => {
  test.skip((page.viewportSize()?.width ?? 0) > 767, '仅移动端验证')
  await loginWithMockApi(page)
  await page.goto('/tasks/new')
  await expect(page.getByText('请在平板或桌面完成任务配置')).toBeVisible()
  await page.goto('/runs/failed-run-id')
  await expect(page.getByRole('button', { name: '安全重试' })).toBeVisible()
  await expectNoHorizontalOverflow(page)
})
