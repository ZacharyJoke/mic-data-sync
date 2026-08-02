# Changelog

本文件记录 mic-data-sync 的重要变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 新增

- 面向开源仓库的社区文档：贡献指南、安全策略、行为准则、架构说明、用户指南、API 参考、开发指南、路线图、术语表与第三方许可说明。

## [0.1.0-SNAPSHOT] - 2026-08-02

### MVP-I1 候选版本（已交付）

- 实例身份与角色（`source` / `sink` / `source,sink`），管理员 Cookie Session + CSRF 登录；
- 端管理：Source 端固定为当前实例，Sink 端支持本地/远程多端维护、探活回填实例 ID、批次认证检查、按端 Sink Token 轮换；
- 多数据源：按所属端维护数据库档案（KingbaseES / openGauss），远程端通过 Agent API 下发保存，连接测试下发到所属端执行；
- 数据库连接配置 AES-GCM 加密存储，驱动本地加载；
- Table 模式（Schema/表/字段/条件/分页键/样例）与单表 SQL 模式（AST 安全校验/字段探查/SQL 到 Table 转换）；
- 任务 CRUD、字段映射、启用前完整校验；
- Sink Readiness 与回执表自动创建/DDL 回退；
- 批次幂等写入：业务 UPSERT + 回执同事务，UPSERT 自动排除目标主键与全部唯一索引列；
- Run 引擎：首次全量（T0 切分 + 自动追赶）、手动增量回看窗口、Keyset 分页、Checkpoint 单调推进；
- 暂停/继续、安全重试、启动恢复、加密 Spool、终态 Spool 7 天清理；
- 结构化运行诊断与工作台异常提示；
- Linux x86_64 分发包（start/stop/systemd/配置模板/运维文档）。

### 已知边界

- 每日调度、全局并发排队、INSERT_ONLY 重复保护、DELETE 传播未交付；
- 多表 JOIN、递归条件树、应用级备份恢复、Webhook、ARM64 正式认证未交付；
- 三方向完整真实 E2E 与 4 小时稳定性基准脚本已交付，当前完成 openGauss → openGauss 双实例实测。
