import { ref, shallowRef } from 'vue'

import type { RunItem } from '@/api/runs'

const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])

/**
 * 活动运行轮询：每 5 秒刷新，页面隐藏时暂停，终态停止，连续失败退避到 15 秒。
 */
export function useRunPolling(fetchRun: () => Promise<RunItem>) {
  const data = shallowRef<RunItem | null>(null)
  const refreshing = ref(false)
  const lastUpdatedAt = ref<Date | null>(null)
  const refreshInterval = ref(5_000)
  let timer: number | undefined
  let consecutiveFailures = 0

  async function refresh() {
    if (document.visibilityState === 'hidden' || refreshing.value) {
      return
    }
    refreshing.value = true
    try {
      data.value = await fetchRun()
      consecutiveFailures = 0
      refreshInterval.value = 5_000
      lastUpdatedAt.value = new Date()
    } catch {
      consecutiveFailures += 1
      if (consecutiveFailures >= 2) {
        refreshInterval.value = 15_000
      }
    } finally {
      refreshing.value = false
      schedule()
    }
  }

  function schedule() {
    window.clearTimeout(timer)
    if (data.value && TERMINAL.has(data.value.status)) {
      return
    }
    if (document.visibilityState === 'visible') {
      timer = window.setTimeout(refresh, refreshInterval.value)
    }
  }

  function onVisibilityChange() {
    if (document.visibilityState === 'visible') {
      void refresh()
    } else {
      window.clearTimeout(timer)
    }
  }

  function start() {
    document.addEventListener('visibilitychange', onVisibilityChange)
    void refresh()
  }

  function stop() {
    window.clearTimeout(timer)
    document.removeEventListener('visibilitychange', onVisibilityChange)
  }

  return { data, refreshing, lastUpdatedAt, refreshInterval, start, stop, refresh }
}
