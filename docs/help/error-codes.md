# mic-data-sync 公开错误码

> 适用版本：MVP 1.0 P0（范围已冻结）  
> 文档状态：随应用版本发布  
> 最近更新：2026-08-18

本文列出 Web UI、管理员 CLI 和公开 API 可以稳定展示给用户的错误码。每个公开错误都必须提供中文说明、是否可重试和处理办法；内部 Java 异常、JDBC 厂商错误码和调试标识不属于本目录。

MVP 1.0 的交付级别以 `docs/README.md` 维护的基线为准。与应用级备份恢复、自定义 truststore 生命周期、目标数据库 Epoch/回退、Sink 重绑定和回执清理窗口有关的条目属于 **P1 保留错误码**，P0 实现不得实际返回或在 UI 中呈现为可用功能。

## 通用

### `VALIDATION_FAILED`

- **含义**：参数或配置校验失败。
- **可直接重试**：否。
- **处理**：根据响应 `details` 修正请求参数或任务配置后重试。

### `UNAUTHORIZED`

- **含义**：未认证或会话已失效。
- **可直接重试**：重新登录后可以。
- **处理**：重新登录后重放请求；登录接口本身不返回业务错误码。

## 数据库、SQL 和任务配置

### `DATABASE_CONNECTION_FAILED`

- **含义**：数据库连接失败（网络不可达、服务未启动或连接参数错误）。
- **可直接重试**：是。
- **处理**：检查网络、数据库服务和连接参数；凭据错误不自动重试，修正后重试。

### `DATABASE_CAPABILITY_BLOCKED`

- **含义**：数据库能力不满足要求（产品/版本不支持或账号权限不足）。
- **可直接重试**：否。
- **处理**：核对支持的产品/版本范围与账号最小权限；能力恢复前任务保持 `BLOCKED`，Sink 不开放接收能力。

### `SQL_NOT_SINGLE_SELECT`

- **含义**：SQL 模式仅支持单条只读 `SELECT`。
- **可直接重试**：否。
- **处理**：改为单条只读 `SELECT`，不允许多条语句或 `INSERT/UPDATE/DELETE`。

### `SQL_UNSUPPORTED_STRUCTURE`

- **含义**：SQL 包含不支持的结构（多表 JOIN、子查询、`LIMIT` 等）。
- **可直接重试**：否。
- **处理**：改为单表只读查询，或由 DBA 创建视图后同步该视图；JOIN 场景参见 `SQL_JOIN_NOT_SUPPORTED`。

### `SQL_RESULT_COLUMN_DUPLICATED`

- **含义**：SQL 结果存在重复列名，无法建立稳定字段映射。
- **可直接重试**：否。
- **处理**：为重复列使用别名后重新保存任务。

### `PAGINATION_KEY_NOT_UNIQUE`

- **含义**：配置的分页 Key 组合在源数据范围内不唯一，无法保证可靠分页。
- **可直接重试**：否。
- **处理**：选择源表主键、唯一索引或可靠稳定唯一组合作为 `paginationKeys`。

### `TARGET_UNIQUE_CONSTRAINT_MISSING`

- **含义**：目标表缺少用于 UPSERT / UPSERT_NO_OVERWRITE（冲突跳过）的稳定唯一约束（主键或唯一索引）。
- **可直接重试**：否。
- **处理**：由 DBA 在目标表创建唯一约束；或改用 INSERT_ONLY 追加型任务（需接受重复执行风险）。

### `REPLACE_ALL_NO_INCREMENT`

- **含义**：REPLACE_ALL（全量重导）任务配置了更新时间字段或尝试执行手动增量；该模式仅支持全量同步。
- **可直接重试**：否。
- **处理**：移除更新时间字段；REPLACE_ALL 任务如需增量能力，应改用 UPSERT / UPSERT_NO_OVERWRITE 并配置唯一 Key。

### `UNSUPPORTED_DATABASE_DIRECTION`

- **含义**：同步方向不受支持（MVP 允许 KingbaseES→openGauss、openGauss→openGauss、openGauss→KingbaseES；禁止 KingbaseES→KingbaseES）。
- **可直接重试**：否。
- **处理**：调整源/目标数据库组合，或按支持的方向重新配置任务。

### `SQL_JOIN_NOT_SUPPORTED`

- **含义**：SQL 使用了多表 JOIN 或其他 MVP 不支持的多表结构。
- **可直接重试**：否。
- **处理**：改成单表 SQL，或由 DBA 创建视图后同步该视图。

### `PAGINATION_KEYS_REQUIRED`

- **含义**：没有配置组合后稳定且唯一的分页 Key。
- **可直接重试**：否。
- **处理**：选择源表主键、全部非空唯一索引，或可靠的稳定唯一组合字段。

### `CURSOR_VALUE_NULL`

- **含义**：增量游标或分页 Key 在任务数据范围内出现 NULL。
- **可直接重试**：否。
- **处理**：修复源数据、通过条件树排除 NULL，或在 SQL 中显式构造稳定非 NULL 的游标；处理后重试原 Run。

### `SYNC_LOOP_DETECTED`

- **含义**：检测到同一业务数据集可能形成 `A→B→A` 循环同步。
- **可直接重试**：否。
- **处理**：禁用或调整其中一个方向的任务。MVP 不提供双向冲突合并。

### `TASK_LIMIT_REACHED`

- **含义**：已达到 Source 任务定义数量上限。
- **可直接重试**：否。
- **处理**：安全删除不再使用的任务，或调整 `MIC_SYNC_SOURCE_MAX_TASKS` 并重启。

### `TASK_ALREADY_RUNNING`

- **含义**：同一任务已有活动 Run。
- **可直接重试**：稍后可以。
- **处理**：等待、继续或终止现有 Run；同一任务不允许并发运行。

## 调度、运行和批次

### `GLOBAL_CONCURRENCY_LIMIT`

- **含义**：全局活动 Run 名额已满，未创建新的 Run。
- **可直接重试**：稍后可以。
- **处理**：等待现有 Run 进入终态，或人工终止不再需要的暂停 Run。`WAITING_RETRY`、`UNKNOWN`、`PAUSED` 仍占用名额。

### `SCHEDULE_MISSED`

- **含义**：每日触发因停机、任务已有 Run、任务不可运行或名额不足而错过。
- **可直接重试**：不自动重试。
- **处理**：需要时手动执行增量；多个错过调度不会补偿或排队。

### `SOURCE_UNAVAILABLE`

- **含义**：Source 数据库发生可识别的临时连接、超时、连接重置、重启、资源不足或事务冲突，当前不能安全完成新的源查询。
- **可直接重试**：是。
- **处理**：系统先进行 5 次指数退避，随后每 5 分钟重试，最长 24 小时。期间 Run 为 `WAITING_RETRY`、Task 保持 `ENABLED` 并占用原名额；超过窗口后以 `RETRY_EXHAUSTED` 暂停。检查网络、数据库状态和连接池，恢复后从最后确认检查点继续。

### `SOURCE_AUTHORIZATION_FAILED`

- **含义**：Source 数据库凭据无效、账号被锁定，或账号缺少任务所需的 SELECT/元数据读取权限。
- **可直接重试**：否。
- **处理**：核对 Source 连接凭据和最小读取权限，完成连接与任务检查后继续原 Run。系统不会自动重试错误凭据，也不会要求写权限。

### `SOURCE_QUERY_FAILED`

- **含义**：新的 Source 查询发生确定性错误，例如 SQL 语法、字段引用、方言或不可归类为临时故障的读取错误。
- **可直接重试**：否。
- **处理**：查看已脱敏的 SQL/字段诊断，修复任务配置或数据库对象后重新检查并继续原 Run。失败不会创建新 Batch、推进检查点或保留不完整 Payload。

### `SOURCE_SCHEMA_DRIFT`

- **含义**：当前源表元数据或 SQL 结果结构与任务保存的读取契约发生变化。兼容变化以警告形式继续；影响映射、过滤、游标、分页稳定性或 SQL 结果唯一性的变化会在新的源查询前暂停 Task 和 Run。
- **可直接重试**：取决于变化类型。兼容变化无需重试；不兼容变化必须恢复原结构或创建新任务版本。
- **处理**：查看字段、唯一约束或 SQL 结果差异。DBA 恢复原结构后重新检查并继续原 Run；如果接受新结构，重新配置字段映射、过滤条件、SQL、增量游标或 paginationKeys，保存新版本并重新首次全量。工具不会自动修改任务读取语义。

### `SCHEMA_DRIFT`

- **含义**：当前目标表实时元数据与任务写入契约发生变化。兼容变化以警告形式继续运行；不兼容变化会在读取 Source 前暂停 Task 和 Run，且不创建新 Batch 或 Spool。
- **可直接重试**：取决于变化类型。兼容变化无需重试；不兼容变化必须先恢复原结构或创建新任务版本。
- **处理**：查看差异详情。新增可空、有默认值或自动生成字段以及兼容容量扩大不会自动加入映射；如果 DBA 恢复原表结构，重新 Preflight 后继续原 Run。如果接受新结构，重新探查目标表、人工调整映射或 UPSERT Key，保存新版本并重新首次全量。工具不会自动 ALTER 业务表或自动映射新字段。

### `CUSTOM_TRUSTSTORE_REQUIRED`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：恢复后的 Source 依赖备份清单中记录的自定义 truststore，但指定文件尚未由运维安装；相关 HTTPS Sink 保持 `BLOCKED`。
- **可直接重试**：安装文件、提供密码并重启后可以。
- **处理**：根据备份清单中的类型、原路径和期望 SHA-256 安装受控 truststore，通过环境变量或受保护秘密文件提供密码，然后重启客户端。不得通过自动开启不安全 TLS 绕过恢复检查。

### `TRUSTSTORE_CHANGED`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：当前自定义 truststore 的 SHA-256 与备份清单或本实例上次确认的值不同，可能是正常证书更新，也可能是未授权替换。
- **可直接重试**：管理员核查并确认后可以。
- **处理**：核对变更来源、文件权限、证书条目和 SHA-256；确认是有意更换后，在 Web UI 中明确确认并更新本实例记录，再重启和重新验证全部 HTTPS Sink。系统不会自动接受新 Hash。

### `TRUSTSTORE_LOAD_FAILED`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：Source 启动时无法加载实例级自定义 JKS/PKCS12 truststore，例如路径不存在、权限不足、密码错误、格式错误或文件损坏。
- **可直接重试**：修复配置并重启后可以。
- **处理**：核对本地 YAML 中的路径、文件权限和类型，并确认环境变量或受保护秘密文件中的密码正确；修复后重启客户端。系统不会自动切换到 JDK 默认信任库、不安全 HTTPS 或 HTTP。

### `SINK_INSTANCE_MISMATCH`

- **含义**：握手返回的 `sinkInstanceId`，或有状态请求携带的 `X-Mic-Expected-Sink-Instance-Id`，与当前 Sink 本地身份不一致。有状态请求在开启数据库事务前以 HTTP 409 拒绝，不写入业务数据或批次回执；活动 Batch 保持 `PENDING`，Task 和 Run 暂停。
- **可直接重试**：修复错误路由后可继续原 Run；不能忽略或手工覆盖身份不匹配。
- **处理**：先核对 URL、DNS、代理和部署是否误指向其他客户端。恢复指向具有原 `sinkInstanceId` 的实例后，复用原批次继续；如果确认更换目标 Sink，则创建新任务版本、重新注册写入契约并重新执行首次全量。

### `TARGET_DATABASE_EPOCH_MISMATCH`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：Source 任务和请求携带的 `X-Mic-Expected-Database-Epoch-Id` 与当前 Sink 目标数据库状态表中的 `database_epoch_id` 不一致。通常表示目标数据库发生了恢复、重建、克隆或切换。Sink 在开启目标数据库事务前以 HTTP 409 拒绝。
- **状态**：`Batch=PENDING`、`Run=PAUSED`、`Task=PAUSED`；不写业务数据或回执，不推进检查点，并保留 Payload、Spool 和原全局名额。
- **处理**：不要直接把任务旧 Epoch 覆盖为当前值。普通不匹配先排查错误路由或尚未完成的恢复；如果经认证确认仍为同一 Sink、Sink 已 `READY` 且新 Epoch 正式生效，由管理员在 UI 中显式终止旧 Run。系统将旧 Run 标记 `CANCELLED`、未完成 Batch 标记 `SUPERSEDED/TARGET_DATABASE_EPOCH_REPLACED` 并释放名额，但不把它们解释为从未在旧 Epoch 提交。随后创建新任务版本、重新注册写入契约并重新执行首次全量；INSERT_ONLY 任务还需要 DBA 清理目标数据或明确接受重复风险。

### `TARGET_DATABASE_BOUND_TO_ANOTHER_SINK`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：目标数据库的 `mic_sync_sink_state` 已绑定另一个 `sinkInstanceId`，当前 Sink 不允许自动创建第二个身份或抢占，因而进入 `NOT_READY`。
- **可直接重试**：否，必须先核对部署或完成受控重绑定。
- **处理**：优先检查是否误连了其他环境的目标数据库。如果确实要更换 Sink，先停止或网络隔离原 Sink，再登录当前 Sink 服务器执行 `mic-data-sync admin rebind-target-database`；必须提供离线确认、准确旧身份并再次完整输入，操作通过事务和乐观锁写入审计并保守推进回执清理边界。Web UI 不提供执行按钮。原 Source 任务不能沿用旧身份，必须创建新任务版本、重新注册契约并重新首次全量。

### `TARGET_DATABASE_STATE_ROLLBACK`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：目标数据库内部状态相对 Sink 本地高水位发生回退，包括 `database_epoch_id` 改变、目标 `commit_sequence` 或 `receiptRetentionCutoff` 变小、状态表缺失或重新初始化。常见原因是目标数据库恢复旧备份、快照回滚、克隆或内部表被重建。
- **可直接重试**：否，禁止普通忽略并继续。
- **处理**：立即保持 Sink `NOT_READY`，不得接收批次或返回证明未提交的 HTTP 404。由 DBA 核实目标数据库恢复范围，停止客户端服务，并在 Sink 服务器执行 `mic-data-sync admin acknowledge-target-database-restore --confirm-all-affected-tasks-require-new-full-sync`。命令建立新 Epoch 后，所有受影响任务必须创建新版本并重新首次全量；INSERT_ONLY 任务还需先清理目标数据或明确承担重复风险。Web UI 不提供忽略按钮。目标数据库和客户端 dataDir 同时回退时可能无法自动检测，运维必须主动申报数据库恢复。

### `TLS_VALIDATION_FAILED`

- **含义**：HTTPS 连接的证书链或目标主机名校验失败，系统没有自动降级为不安全 HTTPS 或 HTTP。启用前发生时任务不能启用；活动 Run 发生时 Task 和 Run 暂停，当前 Batch 保持 `PENDING`。
- **可直接重试**：否，必须先修复证书、主机名或信任库配置，或者由管理员明确承担风险并为该远程 Sink 开启 `tls.insecureSkipVerify`。
- **处理**：核对 Sink URL、证书有效期、SAN/主机名和企业 CA；优先修复证书或配置受信任的 JKS/PKCS12 truststore。重新测试成功后继续原 Run，复用原 `runId/batchId`、Payload 和 Hash；不安全模式只应作为受控内网或临时环境的显式选择。

### `SINK_AUTHENTICATION_FAILED`

- **含义**：远程 Sink 拒绝了 Source 的认证请求，通常以 HTTP 401 返回；不会进一步暴露 Token 是无效、已撤销还是未提供。
- **可直接重试**：否，必须先修复凭据。
- **处理**：在 Sink 创建或确认有效 Token，在 Source 完成认证检查并替换任务凭据，然后继续原 Run；系统复用原 `runId/batchId`、Payload 和 Hash，不重新全量或重置检查点。

### `PROTOCOL_INCOMPATIBLE`

- **含义**：Preflight 握手发现 Source 与 Sink 的 `protocolVersion` 或必需协议能力不兼容。
- **可直接重试**：否，必须先部署协议兼容的应用版本。
- **处理**：核对 Source、Sink 应用版本和发布说明，升级或回滚其中一端后重新执行 Preflight；成功后继续原 Run，不重置检查点或重新首次全量。

### `SINK_NOT_READY`

- **含义**：Preflight 发现 Sink 角色当前不能安全接收数据，例如目标数据库、批次回执表、Sink 状态表、当前实例状态行、本地状态库或磁盘检查未通过。
- **可直接重试**：修复 Sink 后可以。
- **处理**：在 Sink 工作台查看具体阻断原因并完成重新检查；Source 重新执行 Preflight 成功后继续原 Run，不重新读取已经形成 Spool 的批次。

### `SINK_TARGET_NOT_EMPTY`

- **含义**：REPLACE_ALL（全量重导）任务启动时校验目标表应为空，但当前存在数据；工具不执行清表操作。
- **可直接重试**：人工清空目标表后可以。
- **处理**：由线下人工清空目标表后重新触发首次全量；防止未清空导致重复插入。

### `SINK_BUSY`

- **含义**：Sink 暂时过载，通常以 HTTP 429 和 `Retry-After` 返回。
- **可直接重试**：是。
- **处理**：Source 按提示等待，并复用原 `runId/batchId`、Payload 和 Hash 重试。

### `RETRY_EXHAUSTED`

- **含义**：自动重试已达到最长 24 小时窗口。
- **可直接重试**：需要人工确认。
- **处理**：Run 和 Task 保持 PAUSED 且继续占用原名额；检查网络、Sink 和数据库后继续原 Run，或终止 Run 释放名额。

### `BATCH_HASH_CONFLICT`

- **含义**：相同 `sourceInstanceId + batchId` 对应的 Payload Hash 与已有回执或请求不一致。
- **可直接重试**：否。
- **处理**：立即停止盲目重放，检查本地状态库、Spool、实例复制和任务版本；必须使用原始批次内容恢复。

### `BATCH_RESULT_UNKNOWN`

- **含义**：批次事务结果未知（网络中断、超时等），无法确认是否已提交。
- **可直接重试**：是。
- **处理**：查询批次回执；无法确定时复用原 `batchId`/`payloadHash` 重发，禁止创建新批次或推进检查点。

### `BATCH_RECEIPT_WINDOW_EXPIRED`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：回执查询未找到匹配记录，且该 Batch 原始 `receiptProtectionAnchor` 已经落入或早于 Sink 持久化的回执清理边界，Sink 无法安全判断回执是从未存在还是已被清理。对应 HTTP 410。
- **可直接重试**：否。
- **处理**：停止自动写入，不得把普通 404 或过期窗口当作未提交证明。由管理员核查源端 Spool、Sink 回执清理记录和目标业务风险，再决定恢复方案。

## Sink 初始化与本地存储

### `SPOOL_CORRUPTED`

- **含义**：SQLite 中存在批次记录，但正式 Spool 文件缺失、无法通过加密认证、记录大小不符或解密后的实际传输字节 Hash 不匹配。
- **可直接重试**：否。
- **处理**：立即停止该 Run，检查磁盘、文件系统、Master Key、备份恢复过程和人为文件操作。不得使用原 `batchId` 重建 Payload。只有批次从未记录发送意图，或 Sink 在回执保护窗口内明确确认没有回执时，管理员才能显式放弃该批次、将其标记为 `SUPERSEDED`，并从最后确认检查点使用新 `batchId` 重读；匹配成功回执则直接据持久化 `endCursor` 确认批次。回执不可达、结果不确定或窗口过期时禁止重读。

### `SPOOL_DELETE_FAILED`

- **含义**：删除任务时无法清理允许删除的终态 Spool。
- **可直接重试**：修复后可以。
- **处理**：修复文件权限、占用或磁盘问题后重新删除；清理成功前不释放任务数量名额。

## 备份与恢复

### `INSTANCE_RUNNING`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：备份或恢复时客户端仍持有 `dataDir` 锁。
- **可直接重试**：停止服务后可以。
- **处理**：正常停止客户端，确认进程退出后重新执行管理员命令。

### `BACKUP_PERMISSION_UNSAFE`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：无法把明文备份文件创建或确认成仅当前操作系统用户可读写的 `0600` 权限。
- **可直接重试**：更换目录或权限后可以。
- **处理**：使用支持安全权限的受控本地文件系统；工具不会保留不安全半成品。

### `BACKUP_OUTPUT_EXISTS`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：备份输出文件已经存在。
- **可直接重试**：更换路径后可以。
- **处理**：选择新的文件名或由运维手工处理旧文件；MVP 不提供 `--force`。

### `RESTORE_TARGET_NOT_EMPTY`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：目标 `dataDir` 非空。
- **可直接重试**：更换目标后可以。
- **处理**：使用不存在或为空的目录，或先把旧目录安全改名保留；工具不自动覆盖或清空。

### `RESTORE_ASSETS_TARGET_NOT_EMPTY`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：同级 `.restore-assets` 待审核目录非空。
- **可直接重试**：清理或更换目录后可以。
- **处理**：先由运维审核并迁移旧内容，再使用空目录恢复。

### `EXTERNAL_MASTER_KEY_REQUIRED`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：备份使用 EXTERNAL Master Key，但恢复环境没有提供 `MIC_SYNC_MASTER_KEY`。
- **可直接重试**：提供密钥后可以。
- **处理**：通过受控环境变量提供原实例使用的同一主密钥，不要把密钥写入命令行、日志或备份包。

### `MASTER_KEY_MISMATCH`

- **交付级别**：P1 保留，MVP 1.0 P0 不返回。
- **含义**：恢复时提供的外部主密钥与备份清单中的非敏感密钥标识不匹配，或无法解密敏感配置。
- **可直接重试**：使用正确密钥后可以。
- **处理**：核对原实例的密钥托管记录；不允许跳过敏感配置继续恢复。
