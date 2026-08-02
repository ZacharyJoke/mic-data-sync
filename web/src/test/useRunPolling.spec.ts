import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useRunPolling } from '@/shared/composables/useRunPolling'

function setDocumentVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    value: state,
  })
}

const RUNNING = { status: 'RUNNING' } as never
const SUCCEEDED = { status: 'SUCCEEDED' } as never

describe('useRunPolling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    setDocumentVisibility('visible')
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('活动运行每 5 秒刷新，终态后停止', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(RUNNING)
      .mockResolvedValueOnce(SUCCEEDED)
    const polling = useRunPolling(fetcher as never)

    polling.start()
    await vi.advanceTimersByTimeAsync(5_000)
    await vi.advanceTimersByTimeAsync(10_000)

    expect(fetcher).toHaveBeenCalledTimes(2)
    polling.stop()
  })

  it('页面隐藏时暂停，恢复可见后立即刷新', async () => {
    setDocumentVisibility('hidden')
    const fetcher = vi.fn().mockResolvedValue(RUNNING)
    const polling = useRunPolling(fetcher as never)

    polling.start()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(fetcher).not.toHaveBeenCalled()

    setDocumentVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(fetcher).toHaveBeenCalledTimes(1)
    polling.stop()
  })

  it('连续失败后退避到 15 秒', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('network'))
    const polling = useRunPolling(fetcher as never)

    polling.start()
    await vi.advanceTimersByTimeAsync(15_000)

    expect(polling.refreshInterval.value).toBe(15_000)
    polling.stop()
  })
})
