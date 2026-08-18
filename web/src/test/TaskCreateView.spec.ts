import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import TaskCreateView from '@/views/TaskCreateView.vue'
import {
  TASK_WIZARD_STORAGE_KEY,
  useTaskWizardStore,
} from '@/stores/taskWizard'

const httpMock = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
const messageMock = vi.hoisted(() => ({ warning: vi.fn(), success: vi.fn(), error: vi.fn() }))
const routerPush = vi.hoisted(() => vi.fn())
const routeParams = vi.hoisted(() => ({ taskId: undefined as string | undefined }))

vi.mock('@/api/http', () => ({
  default: httpMock,
  toApiErrorInfo: (error: { response?: { data?: { message?: string } } }) => ({
    message: error.response?.data?.message ?? '请求失败',
  }),
}))
vi.mock('element-plus', () => ({
  ElMessage: messageMock,
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(true) },
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: routerPush, replace: vi.fn() }),
  useRoute: () => ({ params: routeParams, query: {}, fullPath: '/tasks/new' }),
  onBeforeRouteLeave: () => undefined,
}))

const TASK = {
  taskId: '11111111-1111-1111-1111-111111111111',
  name: 'patient-sync',
  version: 1,
  lifecycleStatus: 'ENABLED',
  readMode: 'TABLE',
  readDefinition: {},
  targetSchema: 'public',
  targetTable: 'patient_copy',
  writeMode: 'UPSERT',
  uniqueKeys: ['id'],
  fieldMappings: [],
  remoteSinkUrl: 'http://sink:19090/mic-data-sync',
  expectedSinkInstanceId: null,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  latestRun: null,
}

async function mountView(step = 1): Promise<VueWrapper> {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useTaskWizardStore()
  store.patch({ currentStep: step as 1 | 2 | 3 | 4 })

  const wrapper = mount(TaskCreateView, {
    global: {
      plugins: [pinia],
      stubs: { 'el-button': true },
    },
  })
  await flushPromises()
  return wrapper
}

describe('TaskCreateView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    routeParams.taskId = undefined
    httpMock.get.mockResolvedValue({ data: { schemas: [] } })
  })

  it('渲染四步向导', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="task-wizard"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-test^="wizard-step-"]')).toHaveLength(4)
    expect(wrapper.find('[data-test="basic-read-step"]').exists()).toBe(true)
  })

  it('只持久化一份不含密码和令牌的浏览器草稿', () => {
    const store = useTaskWizardStore()
    store.patch({ name: 'patient-sync', remoteSinkUrl: 'http://sink:19090/mic-data-sync' })
    store.persist()

    const stored = JSON.parse(localStorage.getItem(TASK_WIZARD_STORAGE_KEY) ?? '{}')
    expect(stored.name).toBe('patient-sync')
    expect(stored).not.toHaveProperty('password')
    expect(stored).not.toHaveProperty('sinkToken')
  })

  it('预检存在阻断项时不能启用', async () => {
    const wrapper = await mountView(4)
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/tasks/preflight') {
        return Promise.resolve({
          data: {
            valid: false,
            issues: [
              {
                severity: 'BLOCKING',
                code: 'UNSAFE_SQL',
                message: 'SQL 仅允许单条 SELECT',
                field: 'readDefinition.rawSql',
                stage: 'SOURCE_VALIDATION',
                suggestedAction: '移除写操作和多语句',
              },
            ],
          },
        })
      }
      return Promise.reject(new Error(`unexpected post ${url}`))
    })

    await wrapper.find('[data-test="validate-enable"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('SQL 仅允许单条 SELECT')
    expect(httpMock.post).not.toHaveBeenCalledWith('/tasks', expect.anything())
  })

  it('预检通过后保存、启用并展示首次全量动作', async () => {
    const wrapper = await mountView(4)
    httpMock.post.mockImplementation((url: string) => {
      if (url === '/tasks/preflight') {
        return Promise.resolve({ data: { valid: true, issues: [] } })
      }
      if (url === '/tasks') {
        return Promise.resolve({ data: TASK })
      }
      if (url === `/tasks/${TASK.taskId}/enable`) {
        return Promise.resolve({ data: TASK })
      }
      return Promise.reject(new Error(`unexpected post ${url}`))
    })

    await wrapper.find('[data-test="validate-enable"]').trigger('click')
    await flushPromises()

    expect(httpMock.post).toHaveBeenCalledWith('/tasks', expect.anything())
    expect(wrapper.find('[data-test="start-first-full"]').exists()).toBe(true)
    expect(localStorage.getItem(TASK_WIZARD_STORAGE_KEY)).toBeNull()
  })

  it('编辑非草稿任务时锁定语义字段并保存修改', async () => {
    routeParams.taskId = TASK.taskId
    httpMock.get.mockImplementation((url: string) => {
      if (url === `/tasks/${TASK.taskId}`) {
        return Promise.resolve({ data: TASK })
      }
      return Promise.resolve({ data: { schemas: [] } })
    })
    httpMock.put.mockResolvedValue({ data: { ...TASK, name: 'patient-sync-edited' } })

    const wrapper = await mountView(4)

    expect(wrapper.text()).toContain('编辑任务')
    expect(wrapper.find('[data-test="edit-lock-note"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="validate-enable"]').exists()).toBe(false)

    await wrapper.find('[data-test="save-draft"]').trigger('click')
    await flushPromises()

    expect(httpMock.put).toHaveBeenCalledWith(
      `/tasks/${TASK.taskId}`,
      expect.objectContaining({ name: 'patient-sync' }),
    )
    expect(routerPush).toHaveBeenCalledWith({
      name: 'task-detail',
      params: { taskId: TASK.taskId },
    })
  })

  it('保存被后端拒绝时展示后端返回的错误信息', async () => {
    routeParams.taskId = TASK.taskId
    httpMock.get.mockImplementation((url: string) => {
      if (url === `/tasks/${TASK.taskId}`) {
        return Promise.resolve({ data: TASK })
      }
      return Promise.resolve({ data: { schemas: [] } })
    })
    httpMock.put.mockRejectedValue({
      response: {
        status: 409,
        data: {
          code: 'VALIDATION_FAILED',
          message: '任务已启用或已暂停，语义字段不允许直接编辑，请复制或重建任务',
          requestId: 'da15cc50-bacf-4e5e-8fb6-025b5dc6cdcb',
          details: {},
        },
      },
    })

    const wrapper = await mountView(4)

    await wrapper.find('[data-test="save-draft"]').trigger('click')
    await flushPromises()

    expect(httpMock.put).toHaveBeenCalledWith(
      `/tasks/${TASK.taskId}`,
      expect.anything(),
    )
    expect(messageMock.error).toHaveBeenCalledWith(
      '任务已启用或已暂停，语义字段不允许直接编辑，请复制或重建任务',
    )
  })

  it('编辑已禁用任务时不锁定语义字段并可保存', async () => {
    routeParams.taskId = TASK.taskId
    const disabledTask = { ...TASK, lifecycleStatus: 'DISABLED' }
    httpMock.get.mockImplementation((url: string) => {
      if (url === `/tasks/${TASK.taskId}`) {
        return Promise.resolve({ data: disabledTask })
      }
      return Promise.resolve({ data: { schemas: [] } })
    })
    httpMock.put.mockResolvedValue({
      data: { ...disabledTask, targetTable: 'patient_copy_v2' },
    })

    const wrapper = await mountView(4)

    expect(wrapper.find('[data-test="edit-lock-note"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="validate-enable"]').exists()).toBe(true)

    await wrapper.find('[data-test="save-draft"]').trigger('click')
    await flushPromises()

    expect(httpMock.put).toHaveBeenCalledWith(`/tasks/${TASK.taskId}`, expect.anything())
    expect(messageMock.success).toHaveBeenCalledWith('修改已保存')
    expect(routerPush).not.toHaveBeenCalled()
  })

  it('编辑草稿任务保留启用动作', async () => {
    routeParams.taskId = TASK.taskId
    const draftTask = { ...TASK, lifecycleStatus: 'DRAFT' }
    httpMock.get.mockImplementation((url: string) => {
      if (url === `/tasks/${TASK.taskId}`) {
        return Promise.resolve({ data: draftTask })
      }
      return Promise.resolve({ data: { schemas: [] } })
    })

    const wrapper = await mountView(4)

    expect(wrapper.find('[data-test="edit-lock-note"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="validate-enable"]').exists()).toBe(true)
  })

  it('编辑 SQL 草稿时第一步回显保存的 SQL', async () => {
    routeParams.taskId = TASK.taskId
    const sqlTask = {
      ...TASK,
      readMode: 'SQL',
      readDefinition: {
        rawSql: 'select * from mic_sync.patient',
        baseTable: null,
        resultColumns: ['id', 'name', 'status'],
        structureFingerprint: null,
        paginationKeys: ['id'],
        updatedTimeField: 'updated_time',
      },
    }
    httpMock.get.mockImplementation((url: string) => {
      if (url === `/tasks/${TASK.taskId}`) {
        return Promise.resolve({ data: sqlTask })
      }
      return Promise.resolve({ data: { schemas: [] } })
    })

    const wrapper = await mountView()
    await flushPromises()

    const textarea = wrapper.find('[data-test="sql-input"]')
    expect((textarea.element as HTMLTextAreaElement).value).toContain(
      'select * from mic_sync.patient',
    )
  })
})
