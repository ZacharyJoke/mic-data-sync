# REST API 参考

## 通用约定

- Base URL：`http://<host>:19090`
- 管理接口需要管理员登录（Cookie Session + `X-XSRF-TOKEN` 头）；
- Sink 数据通道使用 `Authorization: Bearer <sink-token>`；
- JSON 请求/响应，UTF-8；
- 分页接口使用 `page` / `size` 参数。

## 认证

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/auth/csrf` | 获取 CSRF Token（登录前先调用） |
| `POST` | `/api/v1/auth/login` | JSON `{username, password}` 登录 |
| `POST` | `/api/v1/auth/logout` | 退出登录 |
| `GET` | `/api/v1/auth/me` | 当前登录用户 |

## 系统与实例

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/system/ping` | 存活探测 |
| `GET` | `/api/v1/instance` | 实例 ID、角色、版本与就绪状态 |
| `GET` | `/api/v1/dashboard/summary` | 工作台统计、Sink 总览、异常与最近运行 |

## 端管理

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/endpoints` | 列出端（可按 `role` 过滤） |
| `POST` | `/api/v1/endpoints` | 新增 Sink 端 |
| `GET` | `/api/v1/endpoints/{id}` | 端详情 |
| `PUT` | `/api/v1/endpoints/{id}` | 更新 Sink 端（令牌为空保留原值） |
| `DELETE` | `/api/v1/endpoints/{id}` | 删除 Sink 端（被引用时返回 409） |
| `POST` | `/api/v1/endpoints/{id}/probe` | 探活并回填实例 ID/状态 |
| `POST` | `/api/v1/endpoints/{id}/auth-check` | 批次认证检查 |
| `GET` | `/api/v1/endpoints/{id}/sink-token` | 端级 Sink 令牌掩码 |
| `GET` | `/api/v1/endpoints/{id}/status` | 端状态（含就绪原因与 DBA SQL） |

## 数据源

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/data-sources` | 列出数据源（可按 `endpointId` 过滤） |
| `POST` | `/api/v1/data-sources` | 创建数据源（远程端通过 Agent 下发） |
| `GET` | `/api/v1/data-sources/{id}` | 数据源档案 |
| `PUT` | `/api/v1/data-sources/{id}` | 更新数据源 |
| `DELETE` | `/api/v1/data-sources/{id}` | 删除数据源（被任务引用时返回 409） |
| `POST` | `/api/v1/data-sources/test` | 连接测试（下发到所属端执行） |

## 元数据与 SQL 检查

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/source/metadata/schemas` | 源 Schema 列表 |
| `GET` | `/api/v1/source/metadata/schemas/{schema}/tables` | 表列表 |
| `GET` | `/api/v1/source/metadata/schemas/{schema}/tables/{table}` | 表结构与唯一约束 |
| `POST` | `/api/v1/source/metadata/schemas/{schema}/tables/{table}/sample` | 样例数据（最多 20 行） |
| `POST` | `/api/v1/source/sql/inspect` | SQL 安全校验与字段探查 |
| `GET` | `/api/v1/target/metadata/{schema}/{table}` | 目标表结构与主键 |

## 任务

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/tasks` | 任务列表（分页 + 过滤） |
| `POST` | `/api/v1/tasks` | 创建任务 |
| `GET` | `/api/v1/tasks/{taskId}` | 任务详情 |
| `PUT` | `/api/v1/tasks/{taskId}` | 更新任务 |
| `DELETE` | `/api/v1/tasks/{taskId}` | 删除任务 |
| `POST` | `/api/v1/tasks/preflight` | 集合级预检（无落库副作用） |
| `POST` | `/api/v1/tasks/{taskId}/validate` | 重新校验 |
| `POST` | `/api/v1/tasks/{taskId}/enable` | 启用 |
| `POST` | `/api/v1/tasks/{taskId}/disable` | 禁用 |
| `POST` | `/api/v1/tasks/{taskId}/pause` | 暂停 |
| `POST` | `/api/v1/tasks/{taskId}/resume` | 继续 |

## 运行

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/tasks/{taskId}/runs/full` | 发起首次全量 |
| `POST` | `/api/v1/tasks/{taskId}/runs/incremental` | 发起手动增量 |
| `GET` | `/api/v1/runs` | 运行列表 |
| `GET` | `/api/v1/tasks/{taskId}/runs` | 任务运行历史 |
| `GET` | `/api/v1/runs/{runId}` | 运行详情 |
| `GET` | `/api/v1/runs/{runId}/diagnosis` | 结构化诊断 |
| `GET` | `/api/v1/runs/{runId}/batches` | 批次列表 |
| `GET` | `/api/v1/runs/{runId}/actions` | 可用动作（按状态返回） |
| `POST` | `/api/v1/runs/{runId}/pause` | 暂停 |
| `POST` | `/api/v1/runs/{runId}/resume` | 继续 |
| `POST` | `/api/v1/runs/{runId}/retry` | 安全重试（建议带幂等键） |

## Sink 状态与令牌

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/sink/status` | Sink 就绪状态与批次限制 |
| `GET` | `/api/v1/sink/token` | Sink 令牌掩码 |
| `POST` | `/api/v1/sink/token/rotate` | 轮换 Sink 令牌 |
| `GET` | `/api/v1/sink/source-token` | Source 端令牌状态 |
| `PUT` | `/api/v1/sink/source-token` | 保存 Source 端令牌 |
| `DELETE` | `/api/v1/sink/source-token` | 清除 Source 端令牌，回退部署配置 |

## Sink 数据通道（Bearer Token）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/sink/handshake` | 握手：身份、协议与批次限制 |
| `POST` | `/data/receive/{table}` | 接收业务批次（需 `X-Mic-Expected-Sink-Instance-Id`） |
| `GET` | `/data/receipt/{sourceInstanceId}/{batchId}` | 查询批次回执 |

## Agent（控制台到远程端）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/agent/probe` | 远程端探活 |
| `GET` | `/api/v1/agent/data-sources` | 列出远程端数据源 |
| `POST` | `/api/v1/agent/data-sources` | 下发创建数据源 |
| `PUT` | `/api/v1/agent/data-sources/{id}` | 下发更新数据源 |
| `DELETE` | `/api/v1/agent/data-sources/{id}` | 下发删除数据源 |
| `POST` | `/api/v1/agent/data-sources/test` | 下发连接测试 |
| `POST` | `/api/v1/agent/target/preflight` | 远程目标表预检 |
| `GET` | `/api/v1/agent/sink-token` | 远程端 Sink 令牌掩码 |

## 错误响应

管理接口统一返回：

```json
{
  "code": "VALIDATION_FAILED",
  "message": "参数或配置校验失败",
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "details": {
    "field": "readDefinition.rawSql"
  }
}
```

- `code`：稳定的公开错误码，完整目录见 `docs/help/error-codes.md`；
- `requestId`：用于日志追踪；
- `details`：校验失败时定位到具体字段。

## 版本兼容性

- 当前版本为 MVP 阶段（`0.1.0-SNAPSHOT`），接口可能随迭代调整；
- Sink 数据通道通过 `protocolVersion` 与握手能力协商，升级前建议先升级 Sink 再升级 Source；
- 公开错误码是稳定契约，新增错误码不会复用旧编号。
