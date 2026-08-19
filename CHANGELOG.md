# Changelog

本文件记录 mic-data-sync 的重要变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 新增

- 面向开源仓库的社区文档：贡献指南、安全策略、行为准则、架构说明、用户指南、API 参考、开发指南、路线图、术语表与第三方许可说明。
- 新增 `UPSERT_NO_OVERWRITE` 写入模式：冲突时保留目标行并跳过（无仲裁键退化为 `ON CONFLICT DO NOTHING`）；
- 新增 `REPLACE_ALL` 全量重导写入模式与软唯一键，支持无主键关联表同步；
- 新增双阶段增量策略（`DUAL_PHASE`）：主键推进捕获新增 + 时间窗口补扫更新；
- 批次网络/确认类临时故障按退避间隔自动重试，耗尽后保持 `UNKNOWN` 等待安全恢复；
- 运行详情批次列表新增时间水位列；
- 并发全量下限制读取页字节并复用传输负载，降低内存峰值；
- 分发包内置 openGauss / PostgreSQL JDBC 驱动并优化驱动加载机制；
- 新增远程 Sink 目标元数据查询接口（Schema / 表列表）；
- 支持同主机多实例会话与 CSRF Cookie 名可配置，避免 Cookie 互顶；
- 统一 `/mic-data-sync/` URL 前缀与 nginx 反向代理样例。

### 修复

- 批次字节上限改为读取页预检截断：消除页内“1 行尾批”，大行宽表全量每页只产出一个完整批次，减少 HTTP 传输与 Sink 事务次数；
- 修复无时区时间被转 UTC 导致日期越界与偏移、首次全量缺数据与增量漏读未来时间数据；
- 修复源数值写入目标 boolean 列失败、checkpoint 无时区时间解析；
- 修复全量页取满 batchSize 时标记截断，避免漏同步剩余行；
- 修复本地 Sink 端默认地址未拼接 context-path 前缀；
- 修复后端字符串错误码导致前端解析失败；
- 已禁用/已阻塞任务允许编辑语义字段，任务编辑保存失败时展示后端错误提示。

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
