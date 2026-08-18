import type { AxiosError } from 'axios'
import { describe, expect, it } from 'vitest'

import { toApiErrorInfo, type ApiResponse } from '@/api/http'

describe('toApiErrorInfo', () => {
  it('提取后端字符串错误码响应体中的 message', () => {
    const error = {
      response: {
        status: 409,
        data: {
          code: 'VALIDATION_FAILED',
          message: '由于没有配置更新时间字段，不允许执行手动增量操作',
          requestId: 'req-1',
          details: {},
        },
      },
    } as unknown as AxiosError<ApiResponse>

    expect(toApiErrorInfo(error)).toEqual({
      code: 'VALIDATION_FAILED',
      message: '由于没有配置更新时间字段，不允许执行手动增量操作',
      requestId: 'req-1',
      details: {},
    })
  })

  it('兼容数字错误码响应体', () => {
    const error = {
      response: {
        status: 400,
        data: {
          code: 40001,
          message: '参数错误',
          requestId: 'req-2',
          details: { field: 'page' },
        },
      },
    } as unknown as AxiosError<ApiResponse>

    expect(toApiErrorInfo(error)).toEqual({
      code: 40001,
      message: '参数错误',
      requestId: 'req-2',
      details: { field: 'page' },
    })
  })

  it('无响应体时回退到 Axios 错误文本', () => {
    const error = { message: 'Network Error' } as AxiosError<ApiResponse>

    expect(toApiErrorInfo(error)).toEqual({ message: 'Network Error' })
  })
})
