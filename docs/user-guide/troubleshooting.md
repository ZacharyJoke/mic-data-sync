# 故障排查

## 快速定位

1. 打开「工作台」查看 Sink 端状态与待处理异常；
2. 打开运行详情查看结构化诊断（`run_failure`）与批次状态；
3. 查看 `${MIC_SYNC_DATA_DIR}/logs/app.log` 获取脱敏日志与 `requestId`；
4. 对照[公开错误码](../help/error-codes.md)找到处理办法。

## 常见问题

| 现象 | 可能原因 | 处理 |
|---|---|---|
| Sink 端不是 `READY` | 回执表未初始化、数据库未配置、能力检查失败 | 展开未就绪原因，执行 DBA 初始化 SQL 后重新检查 |
| 批次 401 / `SINK_AUTHENTICATION_FAILED` | Token 未配置、已轮换或按端 Token 与目标不一致 | 在目标 Sink 生成新令牌，并在「端管理」更新对应 Sink 端令牌 |
| 运行 `UNKNOWN` | 网络中断或响应丢失，事务结果未知 | 查询回执；结果不可确定时复用原批次身份安全重试，禁止创建新批次 |
| 批次网络失败自动重试 | 网络/确认类临时故障 | 系统按退避间隔自动重试；耗尽后批次保持 `UNKNOWN`，查询回执后安全重试 |
| 校验报「目标表缺少唯一约束」 | UPSERT 需要真实唯一约束 | DBA 创建主键/唯一索引，或改用 INSERT_ONLY |
| 校验报「REPLACE_ALL 不支持增量」 | REPLACE_ALL 仅支持全量 | 移除更新时间字段，或改用 UPSERT / UPSERT_NO_OVERWRITE |
| `SINK_TARGET_NOT_EMPTY` | REPLACE_ALL 启动时目标表非空 | 由 DBA 线下清空目标表后重新触发首次全量 |
| 手动增量报「未配置更新时间字段 / 缺少唯一 Key / 基准为空」 | 前置校验不满足 | 配置更新时间字段与唯一 Key，先完成一次全量采集后再增量 |
| 任务无法启用 | 预检存在阻断项 | 按预检报告的字段与阶段修正后重新校验 |
| Run 一直 `WAITING_RETRY` | Source/Sink 临时故障 | 检查网络与数据库；超过 24 小时窗口会暂停并占用名额 |
| `SPOOL_CORRUPTED` | Spool 文件缺失、解密失败或 Hash 不一致 | 停止该 Run，检查磁盘与密钥；禁止伪造原批次重读 |
| 删除任务失败 | 存在活动 Run 或 Spool 清理失败 | 先暂停/终止 Run，修复文件权限后重试 |
| 启用任务后无法修改配置 | 启用后语义字段锁定 | 复制或重建任务版本并重新首次全量 |

## 收集诊断信息

向维护者反馈问题时请提供：

- 应用版本（工作台「实例」区域显示）；
- `requestId` 或运行 ID；
- `docs/help/error-codes.md` 中的错误码；
- 相关日志片段（先脱敏：去掉 URL、IP、用户名、Token 与业务数据）。

## 安全事件

如果怀疑 Token、密码或 Master Key 泄露，请立即轮换/重置，并通过 [SECURITY.md](../../SECURITY.md) 描述的渠道私有报告。
