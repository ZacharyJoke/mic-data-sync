# mic-data-sync 文档

> 状态：MVP 1.0 范围已冻结，MVP-I1 候选版本已交付  
> 最近更新：2026-08-02

本目录维护项目文档。公开文档不包含真实服务器地址、密码、Token 或生产实例 ID；测试环境验证记录放在 `docs/internal/`，该目录不随仓库发布。

## 快速入门

| 文档 | 用途 |
|---|---|
| [快速开始](user-guide/quick-start.md) | 用 Docker openGauss 最快跑通一次同步 |
| [配置说明](user-guide/configuration.md) | 环境变量、角色与数据目录 |
| [同步任务使用指南](user-guide/tasks.md) | Table/SQL 模式、映射、写入与运行 |
| [故障排查](user-guide/troubleshooting.md) | 常见问题与诊断步骤 |

## 架构与设计

| 文档 | 用途 |
|---|---|
| [架构总览](architecture/overview.md) | 两级架构、角色与部署形态 |
| [数据流与同步语义](architecture/data-flow.md) | 全量/增量、批次幂等、恢复 |
| [安全模型](architecture/security-model.md) | 认证、Token、TLS、加密与最小权限 |

## API

| 文档 | 用途 |
|---|---|
| [REST API 参考](api/api-reference.md) | 管理接口、Sink 数据通道与错误格式 |

## 帮助与能力边界

| 文档 | 用途 |
|---|---|
| [能力边界](help/support-scope.md) | 支持、有条件支持与不支持的能力 |
| [公开错误码](help/error-codes.md) | 公开错误码及处理办法 |

## 开发与发布

| 文档 | 用途 |
|---|---|
| [开发环境搭建](development/setup.md) | 环境、目录与常用命令 |
| [测试指南](development/testing.md) | 单测、E2E 与冒烟 |
| [发布流程](development/release.md) | 构建分发包与发布检查清单 |

## 部署与运维

| 文档 | 说明 |
|---|---|
| [安装部署手册](operations/installation.md) | Linux x86_64 安装/启动/停止/升级 |
| [首次同步操作手册](operations/first-sync-runbook.md) | Web UI 首次同步完整步骤 |
| [数据库账号与权限](operations/database-accounts.md) | Source/Sink 账号最小权限与回执表初始化 |
| [测试数据库 Docker 部署](operations/docker-databases.md) | openGauss/KingbaseES Docker 部署指引 |

## 其他

| 文档 | 说明 |
|---|---|
| [路线图](roadmap.md) | 当前状态与 P1/P2 计划 |
| [术语表](glossary.md) | 统一术语 |
| [许可证与第三方声明](licensing.md) | MIT 许可证与依赖/驱动许可 |

## 当前状态

- MVP 1.0 范围已经冻结，MVP-I1 候选版本已交付（版本 `0.1.0-SNAPSHOT`）；
- 已交付管理员登录、端管理、多数据源、Table/SQL 任务、预检/启用、首次全量/手动增量、暂停/继续/安全重试、Sink Token 轮换与批次认证；
- 2026-08-02 已完成同服务器双实例跨端验证（openGauss → openGauss），验证记录保留在 `docs/internal/`（不随仓库发布）；
- 每日调度、全局并发排队、INSERT_ONLY 重复保护、DELETE 传播、应用级备份恢复等仍属于后续迭代。

## 文档维护约定

1. 已确认内容写入文档，未确认内容放入“待确认事项”，不得伪装成最终结论。
2. 若新决策推翻旧决策，应修改对应文档并保留“已取代 / 后置为 P1”标记，而不是只追加互相矛盾的说明。
3. 能力与错误码以[能力边界](help/support-scope.md)和[公开错误码](help/error-codes.md)为基线；实现与文档不一致时以代码为准并及时更新文档。
4. 公开文档不得出现真实服务器地址、密码、Token、密钥或生产实例 ID；内部验证记录放在 `docs/internal/`。
