# mic-data-sync 能力边界

> 适用版本：MVP 1.0 P0（范围已冻结）  
> 文档状态：随应用版本发布  
> 最近更新：2026-08-19

本文说明当前版本明确支持、有条件支持和暂不支持的能力。任务配置页面负责就地校验，启用前执行统一检查；运行错误的完整公开目录见[错误码](error-codes.md)。

MVP 1.0 的交付基线由 `docs/README.md` 维护。本文中涉及应用级备份恢复、目标数据库 Epoch/回退、Sink 重绑定、回执自动清理、自定义 truststore 生命周期和专用最终切换向导的完整设计属于 P1，不得在 1.0 UI 中显示为已经可用。

## 1. 数据库支持

### 支持的同步方向

- KingbaseES → openGauss；
- openGauss → openGauss；
- openGauss → KingbaseES。

数据库版本验证与实际能力分开表示：

- `VERIFIED`：产品、版本和 JDBC 驱动组合已经进入当前发布测试矩阵；
- `UNVERIFIED`：属于 KingbaseES/openGauss，但具体版本或驱动组合尚未验证；黄色警告但允许继续；
- `UNSUPPORTED`：数据库产品不属于 MVP 支持范围，不能作为 Source 或 Sink；
- `READY`：当前连接的必要权限、元数据、方言、事务和写入能力检查通过；
- `BLOCKED`：实际能力检查失败，禁止启用任务或开放 Sink 接收。

“未验证”不等于“不兼容”，也不等于 `BLOCKED`，但不构成兼容性承诺。本地替换 JDBC 驱动后会重新识别版本和驱动 Hash。

### 实例连接限制

- 控制台的「端管理」维护端身份：Source 端固定为当前实例；Sink 端可维护多个（本地/远程），探活后回填实例 ID 与状态；
- 「数据源」按所属端维护数据库档案：本地端数据源在本地加密保存，远程端数据源通过 Agent API 下发到所属端保存，控制台只保留目录镜像；
- 一个目标数据库只能绑定一个 Sink `instanceId`；
- 同一客户端可以是 Source-only、Sink-only 或双角色；
- 一个任务从一个 Source 数据源读取一张源表或一条单表 SQL，并写入一个 Sink 端目标数据源的一张目标表；
- MVP 不在客户端启动时自动扫描整个数据库，也不自动推断全库表映射；管理员可以手动刷新元数据，任务向导按需探查当前源表和目标表。

## 2. Web UI 与角色

Web 控制台统一使用六个一级页面：

```text
工作台 / 端管理 / 数据源 / 同步任务 / 运行记录 / 系统管理
```

- 任务与运行记录为统一列表视图；Source-only 配置并执行同步任务，Sink-only 只读查看任务与运行记录；
- 本机双角色任务使用内部调用，不要求填写本机 URL 和 Sink Token；
- 「系统管理」保留导航入口；Sink 就绪状态、多 Sink 总览、批次认证与 Token 管理在「工作台」和「端管理」中完成；
- 角色通过启动配置（`MIC_SYNC_ROLES`）修改并在重启后生效，不适用的同步能力按角色收敛。

## 3. 读取模式

### Table 模式

支持通过 Web UI 选择源表、字段、条件树、分页 Key 和增量游标。

必须配置稳定且组合后唯一的 `read.paginationKeys`。工具优先建议源表主键，其次建议全部非空的唯一索引；没有可靠分页字段的表不能启用可靠同步。分页使用 Keyset Pagination，不使用 OFFSET。

### SQL 模式

支持单条、单表、只读 `SELECT`，包括字段别名、CASE、CAST 和无副作用表达式。暂不支持：

- 多表 JOIN、UNION、CTE、FROM 子查询；
- 用户编写 LIMIT、OFFSET、FETCH；
- DML、DDL、锁、文件、网络或其他副作用语句/函数。

无法解析的 SQL 可以保存为草稿，但不能启用。运行时系统保留原始 SQL，并使用派生表在外层增加增量条件、Keyset 条件、ORDER BY 和 LIMIT。

`SELECT *` 可以使用。点击“检查 SQL”后，系统通过结果元数据探查字段，页面提供“一键展开为明确字段”，并保存结果字段快照和结构指纹。合法 SQL 不要求一定转换成 Table 模式。

## 4. 字段映射

支持：

```text
源字段映射 / 固定值 / 不写入
```

- 名称完全一致时可以自动映射；名称不一致时只推荐，不强行映射；
- “不写入”只允许用于目标字段可空、有默认值或自动生成的情况；
- 目标字段为 NOT NULL、无默认值且非自动生成时，必须映射源字段或固定值，否则禁止启用；
- 源 `NULL` 与空字符串严格区分；
- UPSERT Key 不参与 UPDATE。

## 5. 全量、增量与调度

- 首次同步执行全量读取，并使用源数据库高水位、每批短只读事务和全量后的自动追赶；
- 后续支持手动增量；每日固定时间调度属于后续迭代，MVP-I1 候选版本未交付调度配置项；
- 增量支持 `TIME_WINDOW` 与 `DUAL_PHASE`（双阶段：主键推进捕获新增 + 时间窗口补扫更新）两种策略；
- UPSERT / UPSERT_NO_OVERWRITE 增量支持“更新时间字段 + 唯一 Key”或单调递增唯一 Key；更新时间模式默认回看已确认检查点之前 10 分钟的数据，可按任务调整或关闭，并由目标唯一约束 UPSERT 消化跨 Run 重读；
- INSERT_ONLY 增量只允许严格单调递增、全局唯一且不会复用或回拨的源端 Key，不支持更新时间回看；
- 回看窗口只能降低有限时长的晚提交漏数风险，不能解决无限长事务、任意旧时间写入或超出窗口的时间回拨；
- 分页 Key 和增量游标的实际值必须非 NULL；
- 没有可靠游标的数据只能评估为全量任务，无法提供可靠增量同步；
- MVP 不提供 CDC、日志解析、触发器捕获或跨整个全量过程的长事务快照。

## 6. 写入方式和删除语义

### UPSERT

目标表存在主键或唯一索引时，根据配置 Key 执行“存在则 UPDATE，不存在则 INSERT”。配置 Key 必须与目标数据库真实约束匹配。

### UPSERT_NO_OVERWRITE

目标表存在主键或唯一索引时，根据配置 Key 执行“存在则跳过、不存在则 INSERT”；冲突行保留目标已确认值，不覆盖。适用于“目标优先、源不覆盖”场景；无仲裁键时可退化为 `ON CONFLICT DO NOTHING`。

### INSERT_ONLY

没有唯一 Key 时只能使用 INSERT_ONLY，并明确标记为追加型任务：

- 网络重试复用原 `runId/batchId`，由批次回执保证同一批次不重复；
- “继续上一次未完成全量”复用原 Run；
- “发起新的全量”创建新 Run，不能提供跨运行业务去重；
- 每个新的全量 Run 前必须确认 DBA 已准备目标表，或保留目标已有数据并接受重复/约束冲突风险；
- 工具不自动执行 TRUNCATE 或 DELETE。

### REPLACE_ALL（全量重导）

无主键关联表等需要整表重建的场景可以使用 REPLACE_ALL：

- 仅支持全量同步，不允许配置更新时间字段或执行手动增量（错误码 `REPLACE_ALL_NO_INCREMENT`）；
- 首次真正写入批次前校验目标表为空，非空拒绝（错误码 `SINK_TARGET_NOT_EMPTY`）；工具不执行清表操作，由 DBA 线下清空后重试；
- 分页使用 OFFSET 快照分页，不依赖唯一键，要求同步期间源表静止；
- 支持软唯一键：无主键的表可配置业务上组合唯一但数据库无约束的字段组合，供冲突定位使用。

### 删除语义

MVP 只执行 INSERT 和 UPDATE，不向目标表传播 DELETE。软删除字段可以作为普通字段同步；改变过滤条件或源数据不再匹配，不会删除目标历史数据。

工具不创建、修改或删除目标业务表，不创建触发器或业务唯一约束。

MVP 认为 Source 对任务映射字段具有权威性。Sink UPDATE 只修改映射字段，未映射字段保持目标原值，UPSERT Key 只用于定位而不更新。目标业务系统若并发修改同一映射字段，最终值由数据库提交顺序决定；工具不提供冲突检测、自动合并或表锁。首批同步发生在目标系统投产前时，建议在首次全量、自动追赶和短期小批量增量期间保持目标业务系统停机或只读。小批量不属于新模式，仍使用普通增量、分页、检查点和回执。任务不会按日期自动到期。

MVP 1.0 的最终切换采用人工流程：管理员在外部停止 Source 新写入，确认在途事务已经结束，执行最后一次普通手动增量，等待 Run 成功且没有活动 Batch 或未确认 Spool，完成人工核查后禁用任务，再由外部运维开放目标系统写入。P0 不提供 `FINAL_CUTOVER` 专用 Run、不可变切换记录或专用切换向导；这些能力后置为 P1。

每个新的首次全量或重新全量 Run 前，管理员必须选择“DBA 已按业务要求准备目标表”或“保留目标已有数据并接受风险”。UPSERT 不会删除源端不存在的目标行；INSERT_ONLY 不提供跨 Run 去重。Sink 账号若额外具备目标表 SELECT 权限，页面可以显示带时间的行数提示；无法查询不阻断，行数也不能替代人工确认。继续同一个未完成全量 Run 不重复确认。

## 7. 批次幂等、失败恢复和全局名额

Sink 在同一目标数据库事务中写入业务数据和 `mic_sync.mic_sync_batch_receipt` 回执。

- 相同批次和 Hash 返回已有成功结果；
- 相同批次不同 Hash 拒绝写入；
- 业务数据错误整批回滚，`Batch=FAILED`、`Run=PAUSED`、`Task=PAUSED`；
- 网络结果不确定时 `Batch=UNKNOWN`、`Run=UNKNOWN`、`Task=ENABLED`，必须查询回执或复用原批次身份重发；
- 修复后重试原批次复用原 `runId/batchId`；
- 不自动跳过坏行或坏批次。

批次网络/确认类临时故障按退避间隔（30s / 120s / 600s，共 3 次）自动重试，期间 Run 保持 `WAITING_RETRY`；
耗尽后保持批次 `UNKNOWN`，等待人工确认或安全重试；确定性错误直接 `FAILED`。

默认最多 1 个活动 Run（可通过 `MIC_SYNC_SOURCE_MAX_ACTIVE_RUNS` 修改并重启生效），且不存在等待队列。`RUNNING`、`WAITING_RETRY`、`UNKNOWN`、`PAUSED` 均持续占用原名额；只有 `SUCCEEDED`、`FAILED`、`CANCELLED` 释放名额。终止暂停 Run 后任务仍保持暂停，恢复任务后才能开始新 Run。

## 8. 回执表和权限

Sink 启动时检查目标数据库中的 `mic_sync.mic_sync_batch_receipt`，不存在则尝试自动创建。无 DDL 权限时 Sink 进入 `NOT_READY`、不开放接收能力，并展示回执表、索引和授权的完整初始化 SQL；DBA 执行后可以重新检查。工具不自动创建或修改目标业务表、主键和唯一索引。

P0 最小权限：

```text
Source：源对象 SELECT
Sink 业务表：INSERT、UPDATE
Sink 回执接收：SELECT、INSERT
```

P0 不自动清理回执，不要求 Sink 账号永久拥有 DELETE 权限。`mic_sync_batch_receipt` 属于工具状态，DBA 不应手工删除、改写或截断；若发生此类操作，旧 UNKNOWN Batch 可能无法安全判断，任务必须暂停并人工重新评估。

`mic_sync_sink_state`、回执保护锚点、自动清理边界、目标数据库 Epoch/提交序号、回退检测、离线重绑定 CLI 和恢复版本均后置为 P1。MVP 1.0 中一旦目标数据库被恢复、回滚、克隆或替换，或者 Sink 的 dataDir/`instanceId` 丢失，必须停止旧任务，重新评估目标数据并创建新任务首次全量；工具不自动检测这些事件，也不允许运维假定旧检查点仍然有效。

## 9. 数据类型和传输大小

首版重点支持整数、BIGINT、DECIMAL/NUMERIC、REAL/DOUBLE、字符串、中文、NULL、空字符串、BOOLEAN、DATE、TIME、无时区/带时区 TIMESTAMP、UUID、JSON/JSONB，以及 TEXT/LONGTEXT/CLOB。

协议规则：

- 整数、BIGINT、DECIMAL/NUMERIC、REAL/DOUBLE 使用字符串，避免 JSON 精度损失；
- NaN 和正负 Infinity 拒绝传输；
- BOOLEAN 使用 JSON 布尔值；DATE/TIME/TIMESTAMP 使用明确 ISO 表示；
- 无时区时间保持业务字面值，带时区时间保持绝对时间并规范化为 UTC；
- NULL 与空字符串严格区分；
- 暂不支持 BLOB、BYTEA、BINARY。

默认限制：单字段 4 MiB、单批次解压后 16 MiB。达到 64 KiB 的远程批次默认使用 Gzip；超限时不截断数据。

读取页采用字节预检截断：若装入下一行会使累计字节超过负载上限，本页提前停止、
该行留给下一页 Keyset/OFFSET 续读，避免页内切批产生 1 行尾批（大行宽表每页只产出
一个完整批次，减少 HTTP 传输与 Sink 事务次数）。单行本身超过上限的巨行仍单独成页，
属于正常行为。并发全量下限制读取页字节并复用传输负载，降低内存峰值；批次详情展示
时间水位列。

## 10. 简单限速

设计提供限速档位（标准 500 行/批、批次间隔 0 ms；低负载 200 行/批、批次间隔 500 ms；自定义），MVP-I1 候选版本未交付限速 UI。批次行数与字节上限由部署配置控制：`MIC_SYNC_SINK_MAX_ROWS_PER_BATCH`（默认 1000）、`MIC_SYNC_SINK_MAX_PAYLOAD_BYTES`（默认 16 MiB）。每个任务只有一个在途批次，字节上限独立生效。Sink 过载时可返回 `429 + Retry-After`。MVP 不提供精确行/秒、带宽上限或自适应调速。

## 11. 目标序列和结构变化

- 目标 SERIAL、IDENTITY、SEQUENCE 默认由目标数据库生成；
- 保留源主键值时会先探测显式写入能力；
- 全量后如果目标序列落后，任务暂停并展示 DBA 修正 SQL；DBA 修正后必须重新检查通过才能恢复；
- 每个 Run Preflight 只实时探查当前目标表：新增可空、有默认值或自动生成字段，以及兼容的容量扩大，继续运行并产生 `SCHEMA_DRIFT` 警告；目标表、映射字段、UPSERT 唯一约束或写入能力发生不兼容变化时，在读取 Source 前暂停。
- 兼容漂移不自动增加字段映射；不兼容漂移如果由 DBA 恢复原结构，可以继续原 Run，如果接受新结构则必须人工保存新任务版本并重新首次全量。
- Sink Preflight 通过后、执行新的源查询前，Table 模式只检查当前源表使用的字段、过滤字段、增量游标、分页 Key 和唯一约束；SQL 模式重新执行单表只读校验和零数据结果结构探查。
- Source 新增未使用字段或 `SELECT *` 新增未映射结果字段只产生 `SOURCE_SCHEMA_DRIFT` 警告；映射/过滤/游标/分页字段缺失或不兼容、分页唯一约束失效、SQL 结果重名或不再是单表只读查询时暂停。
- Source 漂移也不自动修改映射、SQL、游标或分页 Key；恢复原结构可以继续原 Run，接受新结构必须保存新版本并重新首次全量。
- 恢复未完成 Run 时，如果已有 PENDING/UNKNOWN Batch 和 Spool，先完成 Sink Preflight，再查询回执或复用原 `batchId/Payload/Hash` 重发；这一过程不要求 Source 当前可访问。
- 已有批次确认并推进检查点后，只有在读取下一批新数据前才执行 Source Structure Preflight；失败不会回滚已确认批次，也不会重建原 Payload。
- Source 临时连接、超时、重置、数据库重启或可识别临时资源/事务故障进入 `WAITING_RETRY`，按“5 次指数退避、随后每 5 分钟、最长 24 小时”自动重试，并持续占用原名额。
- Source 凭据或 SELECT 权限错误、确定性 SQL/字段错误和结构不兼容立即暂停，不自动重试。Source 重试从最后确认检查点开始，不创建新 Batch、不推进检查点；未完成的局部结果丢弃。

## 12. 任务数量、删除和本地 Spool

- Source 默认最多保存 10 个任务，可通过 `MIC_SYNC_SOURCE_MAX_TASKS` 修改并重启生效；
- 容量验收可以覆盖为 100，但不是默认值；
- 所有持久化任务状态均计入上限；
- Spool 用于保存未确认批次，支撑网络重试、回执核对和断点恢复；
- 新批次先写入 Master Key 加密的 `.part`，完整读取、可选 Gzip、计算实际传输字节 Hash、fsync 并原子改名后，才在 SQLite 创建 `Batch=PENDING`；提交前禁止发送；
- 启动时清理遗留 `.part` 和没有 Batch 记录的孤儿正式文件；记录存在但文件缺失、解密失败、大小或 Hash 不一致时返回 `SPOOL_CORRUPTED` 并暂停，不能重新查询 Source 伪造原批次；
- 每次发送前先持久化发送意图；找到匹配成功回执时使用已保存的 `endCursor` 推进检查点；
- P0 发现 Spool 损坏、缺失或 Hash 不一致时安全暂停并保留诊断，不提供“证明未提交后标记 SUPERSEDED 并重读”的引导式修复；该能力后置为 P1；
- Gzip 批次的 Hash 基于压缩后的传输字节；Spool 在磁盘上仍加密，重试解密同一不可变内容，不重新压缩；
- 失败/取消/被替代的终态 Spool 默认保留 7 天，也会在安全删除所属任务时自动清理；
- UNKNOWN Spool 不能直接删除，必须先完成回执核对；
- 删除文件失败时任务删除不完成，也不释放任务数量名额。

## 13. 认证和传输

远程 Sink 的 URL 是连接位置，客户端首次验证时保存对方握手返回的 `sinkInstanceId`。修改 URL 后必须重新完成 TLS、Token、协议能力和写入契约检查；只有返回相同 `sinkInstanceId` 才能继续原任务和检查点，不同身份必须创建新任务并重新首次全量，不能手工忽略。

连接测试不是唯一身份校验。任务绑定、数据批次、回执查询和 UNKNOWN 恢复等有状态请求必须携带 `X-Mic-Expected-Sink-Instance-Id`；Sink 在认证后、开启事务前校验，不匹配时返回 HTTP 409 `SINK_INSTANCE_MISMATCH`，且不写业务表或回执、不推进检查点。目标数据库 Epoch 的请求绑定后置为 P1。

每个新 Run 在获得全局名额并创建 Run 记录后、读取 Source 前执行 Sink Preflight；客户端重启续跑、恢复 Run或修改 URL、Token、TLS 后也重新执行。Preflight 验证 TLS、Token、实例身份、协议、Sink 就绪状态和当前目标表写入契约。失败时暂停且不读取源数据、不产生新 Batch 或 Spool；每个批次不重复完整握手。

一个 Sink URL 必须稳定映射到单个客户端实例。允许反向代理和 TLS 终止，但不能轮询到多个独立 Sink。P0 不提供 Sink 集群、主备自动选举、多活、目标数据库内身份重绑定或自动脑裂检测。

远程 Source 使用 HTTP 或 HTTPS 调用 Sink，并通过单个静态 Sink Token 认证：

```http
Authorization: Bearer <sink-token>
```

- HTTPS 可选但推荐；正常 HTTPS 校验证书链和目标主机名；HTTP 页面持续显示明文传输风险；
- 每个远程 Sink 可以显式开启 `tls.insecureSkipVerify`，同时跳过证书链和主机名校验；默认关闭、不得自动降级，保存时二次确认并持续显示红色风险；
- 严格 TLS 校验失败时不得启用或继续新的发送；修复外部 JDK 信任配置或明确开启不安全模式后，复用原批次继续；
- P0 使用外部 JDK 标准 truststore，不提供应用级自定义 JKS/PKCS12 文件、密码、Hash 和恢复生命周期管理；
- Sink 访问令牌可按端配置：每个 Sink 端可保存独立令牌并轮换；未配置时发送批次回退到 Source 部署级 `mic.sync.sink-token`（环境变量 `MIC_SYNC_SINK_TOKEN`）；
- P0 每个 Sink 使用一个静态 Token；更换 Token 采用维护窗口，暂停活动运行、两端修改并验证后继续；多 Token 并存和零停机轮换后置为 P1；
- Sink Token 不绑定目标表白名单；
- 本机双角色内部调用不要求 URL 和 Token；
- MVP 不提供 OAuth2、双向 mTLS 或独立认证中心。

## 14. 备份和恢复（P1）

MVP 1.0 不提供 `.micbak`、`backup create/verify/restore`、保留原 `instanceId` 的恢复、自定义 truststore 恢复或 Web 备份入口。相关完整设计已经保留为 P1，但不得在 P0 UI 和 CLI 中显示为可用能力。

运维自行复制文件不构成工具承诺的可恢复备份。SQLite、Master Key、Spool、配置和驱动若不是在一致停机状态下成套保存，可能无法安全续跑。MVP 1.0 中 dataDir、Master Key 或 Sink 身份丢失后，应停用旧任务并重新首次全量，而不是尝试拼接旧检查点。

## 15. 部署和运行环境

- Linux x86_64 和 aarch64；
- 目标生产环境包括 Kylin-Server-V10-SP3-2403-Release-20240426-aarch64；
- 使用外置 Java 21，推荐生产 ARM64 使用毕昇 JDK 21；
- 默认端口 19090；
- root 运行只告警，不阻断；
- 分发包默认内置 openGauss / PostgreSQL JDBC 驱动并允许本地替换，不支持 Web UI 上传；KingbaseES 驱动为商业授权组件，需按许可自行放置；
- Spring Boot 通过 BOM 固定 3.5.5，前端使用 Vue 3。

## 16. MVP 暂不提供

- CDC、消息队列、分布式事务；
- 多表 SQL、双向循环同步和冲突合并；
- 目标 DELETE、自动业务数据对账；
- BLOB/BYTEA/BINARY；
- Web JDBC 驱动上传、Web 在线升级；
- OAuth2、mTLS、独立认证中心；
- 多实例共享状态库、分布式锁和共享 Spool；
- 通过 Web UI 查看或下载原始 Payload、业务行或 Spool 文件；
- 应用级备份/恢复 CLI、Webhook、零停机 Token 轮换和应用级自定义 truststore 管理；
- 目标数据库 Epoch/回退检测、Sink 身份重绑定、高级损坏 Spool 修复和专用最终切换向导。

## 17. 帮助和错误码

本页只维护能力边界。所有对用户公开且稳定的错误码统一维护在[错误码目录](error-codes.md)。UI 错误卡片的“查看处理方法”必须直接跳转到对应错误码锚点；Java 异常类、JDBC 内部错误和调试标识只进入脱敏日志或诊断包。


## 当前交付状态（2026-08-18）

**MVP-I1 候选版本**：Web 控制台内置于客户端，管理员可通过 UI 管理端与数据源、
创建 Table/SQL 任务、校验启用、执行首次全量与手动增量、查看运行进度与结构化诊断，
并支持暂停/继续/安全重试。

### 已交付能力

- 实例身份与角色（source/sink/source,sink）、管理员 Cookie Session + CSRF 登录；
- 端管理：Source 端固定为当前实例，Sink 端支持本地/远程多端维护、探活回填实例 ID、
  批次认证检查、按端 Sink Token 轮换；
- 多数据源：按所属端维护数据库档案（KingbaseES/openGauss），远程端通过 Agent API
  下发保存，连接测试下发到所属端执行；
- 数据库连接配置 AES-GCM 加密存储、驱动本地加载；
- Table 模式（Schema/表/字段/AND 条件/分页键建议/20 行预览）与单表 SQL 模式
  （AST 安全校验/字段探查/SQL→Table 转换）；
- 任务 CRUD、字段映射、启用前完整校验（方向/Sink/结构/类型/唯一约束/回执 READY）；
- Sink Token（Bearer 认证/掩码/轮换）、握手与 Readiness（回执表自动创建/DDL 回退）；
- 批次幂等写入（业务 UPSERT / UPSERT_NO_OVERWRITE / INSERT_ONLY / REPLACE_ALL + 回执同事务、
  DUPLICATE/Hash 冲突；UPSERT 自动排除目标主键与全部唯一索引列）；
- Run 引擎（全量 T0 切分 + 自动追赶、手动增量回看窗口、双阶段增量策略、Keyset 分页、
  Checkpoint 单调推进）；
- 批次网络故障退避重试与 UNKNOWN 批次恢复、并发全量内存优化；
- 暂停/继续、安全重试、启动恢复、Spool 7 天清理；加密 Spool（AES-GCM/原子提交）；
- 结构化运行诊断（`run_failure`）与前端危险动作按后端可用性渲染；
- Linux x86_64 分发包（start/stop/systemd/nginx/配置模板/内置驱动/运维文档）。

### 候选版本边界（不在本迭代）

- 每日调度、全局并发排队、INSERT_ONLY 重复保护、DELETE 传播；
- 多表 JOIN、递归条件树、备份恢复、Webhook、ARM64 正式认证；
- 批次明细持久化与 Spool 占用指标的 UI 展示（结构已预留）；
- 三方向完整真实 E2E 与 4 小时稳定性基准（脚本已交付；已实测 openGauss → openGauss
  同服务器双实例跨端同步）。
