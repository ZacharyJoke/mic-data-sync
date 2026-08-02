# 架构总览

## 定位

mic-data-sync 是一个面向医疗信息化场景的轻量数据同步工具，采用「Source 客户端 → Sink 客户端 → 目标数据库」的两级架构。它不依赖 CDC、消息队列或分布式事务，通过批次回执、检查点和加密 Spool 保证跨进程重试下的幂等写入。

## 两级架构

```text
源数据库（KingbaseES / openGauss）
        │ 只读 SELECT（Keyset 分页）
        ▼
   ┌─────────────┐      HTTPS / HTTP       ┌─────────────┐
   │ Source 客户端 │ ──── Batch + 回执 ────▶ │ Sink 客户端  │
   └─────────────┘ ◀──── Receipt / 409 ──── └─────────────┘
                                                  │ UPSERT / INSERT_ONLY
                                                  ▼
                                       目标数据库（KingbaseES / openGauss）
```

## 核心角色

| 角色 | 职责 |
|---|---|
| Source | 按任务读取源表或单表 SQL，分页切批，写入加密 Spool 后发送给 Sink，负责检查点推进与重试 |
| Sink | 校验握手身份、Token、TLS 与写入契约，在同一目标数据库事务中写入业务数据与批次回执 |
| Web 控制台 | 内置于客户端，提供端管理、数据源管理、任务配置、预检启用、运行监控与故障诊断 |
| Agent | 控制台向远程 Sink 端下发数据源配置、连接测试与元数据探查的通道 |

## 部署形态

- **单实例双角色**：一个客户端同时启用 `source,sink`，本机任务使用内部调用，不要求填写本机 URL 与 Token；
- **双实例**：Source 与 Sink 分别部署，Source 通过远程 Sink 的 URL 与 Token 发送批次；
- **多 Sink 端**：一个控制台可维护多个 Sink 端（本地/远程），任务绑定具体 Sink 端与目标数据源。

## 代码模块

```text
server/   Spring Boot 3.5 后端，唯一 Maven 业务模块，内置前端静态资源
web/      Vue 3 + Element Plus 管理台 SPA
scripts/  开发、验证、构建、冒烟脚本
e2e/      三方向真实数据库端到端测试与故障注入
distribution/  分发包源文件（bin/config/systemd）
docs/     用户指南、架构、API、开发与运维文档
```

## 设计边界

- 一个任务读取一张源表或一条单表 SQL，写入一张目标表；
- 每批次在 Sink 的事务中写入业务数据和回执，回执与业务提交原子一致；
- Source 与 Sink 不共享状态库；Sink 身份通过握手与 `sinkInstanceId` 绑定；
- 当前版本不提供 CDC、消息队列、分布式事务、双向同步冲突合并和目标表 DELETE 传播。
