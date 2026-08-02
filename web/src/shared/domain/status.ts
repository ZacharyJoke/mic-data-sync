export type StatusTone = 'primary' | 'success' | 'warning' | 'danger' | 'neutral'

export type StatusIconName =
  | 'CircleCheckFilled'
  | 'CircleClose'
  | 'CircleCloseFilled'
  | 'Clock'
  | 'Delete'
  | 'EditPen'
  | 'InfoFilled'
  | 'Loading'
  | 'RemoveFilled'
  | 'VideoPause'
  | 'WarningFilled'

export interface StatusMeta {
  label: string
  tone: StatusTone
  icon: StatusIconName
}

const RUN_STATUS: Record<string, StatusMeta> = {
  RUNNING: { label: '运行中', tone: 'primary', icon: 'Loading' },
  WAITING_RETRY: { label: '等待重试', tone: 'warning', icon: 'Clock' },
  UNKNOWN: { label: '结果未知', tone: 'warning', icon: 'WarningFilled' },
  PAUSED: { label: '已暂停', tone: 'warning', icon: 'VideoPause' },
  SUCCEEDED: { label: '成功', tone: 'success', icon: 'CircleCheckFilled' },
  FAILED: { label: '失败', tone: 'danger', icon: 'CircleCloseFilled' },
  CANCELLED: { label: '已取消', tone: 'neutral', icon: 'RemoveFilled' },
}

const TASK_STATUS: Record<string, StatusMeta> = {
  DRAFT: { label: '草稿', tone: 'neutral', icon: 'EditPen' },
  ENABLED: { label: '已启用', tone: 'success', icon: 'CircleCheckFilled' },
  PAUSED: { label: '已暂停', tone: 'warning', icon: 'VideoPause' },
  DISABLED: { label: '已禁用', tone: 'neutral', icon: 'CircleClose' },
  BLOCKED: { label: '已阻塞', tone: 'danger', icon: 'WarningFilled' },
  DELETING: { label: '删除中', tone: 'warning', icon: 'Loading' },
  DELETED: { label: '已删除', tone: 'neutral', icon: 'Delete' },
}

function fallback(status: string): StatusMeta {
  return {
    label: status,
    tone: 'neutral',
    icon: 'InfoFilled',
  }
}

export function getRunStatusMeta(status: string): StatusMeta {
  return RUN_STATUS[status] ?? fallback(status)
}

export function getTaskStatusMeta(status: string): StatusMeta {
  return TASK_STATUS[status] ?? fallback(status)
}
