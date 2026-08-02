# 数据流与同步语义

## 首次全量

1. 管理员通过任务详情发起「首次全量」；
2. Run 引擎创建 Run 记录，先执行 Sink Preflight（TLS、Token、实例身份、协议、Sink 就绪、目标表写入契约）；
3. 读取源数据库高水位（T0），使用 Keyset 分页按批读取，每批使用短只读事务；
4. 批次先写入 Source 本地加密 Spool（原子提交），再发送给 Sink；
5. Sink 在目标数据库事务中执行 UPSERT/INSERT_ONLY 并写入 `mic_sync_batch_receipt` 回执；
6. Source 收到成功回执后推进 Checkpoint；
7. 全量完成后自动执行追赶（Catch-up），把 T0 之后产生的新数据继续增量同步。

## 手动增量

- 通过任务详情手动触发；
- 更新时间字段模式：默认回看已确认检查点之前 10 分钟，降低晚提交漏数风险，由目标唯一约束消化跨 Run 重读；
- 单调递增唯一 Key 模式：只读取严格大于上次游标的数据；
- INSERT_ONLY 只允许严格单调递增、全局唯一且不会复用/回拨的源端 Key，不支持更新时间回看。

## 批次幂等

```text
Source                                Sink
   │ 生成 Batch + Payload Hash           │
   │ 加密 Spool 原子落盘 ───────────────▶│ 校验 Token/身份/协议/写入契约
   │ 发送                              │ 同事务：业务 UPSERT + 回执
   │ ◀──────── 成功回执 ─────────────── │
   │ 推进 Checkpoint                     │
```

- 相同 `sourceInstanceId + batchId` 与相同 Payload Hash：Sink 返回已有成功结果；
- 相同批次但 Hash 不一致：拒绝写入并返回 `BATCH_HASH_CONFLICT`；
- 事务结果未知（网络中断、超时）：Batch 保持 `UNKNOWN`，Source 必须查询回执或复用原批次身份重发，禁止创建新批次；
- 每个任务同时只有一个在途批次。

## 失败与恢复

| 阶段 | 行为 |
|---|---|
| Source 临时故障（连接、超时、重启） | `WAITING_RETRY`，5 次指数退避后每 5 分钟重试，最长 24 小时 |
| Source 凭据/权限错误 | 暂停，不自动重试，修复后重新检查并继续 |
| SQL/结构不兼容 | 暂停 Run 与 Task，恢复原结构或保存新任务版本 |
| Sink 未就绪/过载 | Preflight 失败或 `429 + Retry-After`，修复后继续原批次 |
| 客户端重启 | 启动恢复扫描非终态 Run，优先核对回执，从最后确认检查点续跑 |
| Spool 损坏 | 暂停并保留诊断，禁止伪造原批次重读 |

## 结构预检

- 每个新 Run 在读取 Source 前执行 Sink Preflight；
- 兼容的结构漂移产生警告并继续；影响映射、过滤、游标、分页或唯一约束的变化会暂停；
- 工具不会自动修改业务表、自动映射新字段或自动 ALTER。
