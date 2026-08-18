# 安全模型

## 管理员认证

- 管理员账号通过 `MIC_SYNC_ADMIN_PASSWORD` 在首次启动时初始化；
- 登录建立 Cookie Session，前后端使用 Spring Security CSRF Token 防护；
- 会话与 CSRF Cookie 名均可配置（`MIC_SYNC_SESSION_COOKIE` / `MIC_SYNC_CSRF_COOKIE`）：同主机部署多个实例时各实例必须配置不同值，避免浏览器同域共享同名 Cookie 导致会话/CSRF 互顶；
- 除登录页外，所有 Web 页面与 `/api/v1/*` 管理接口都需要认证。

## Sink 数据通道认证

- 远程 Source 调用 Sink 使用 `Authorization: Bearer <sink-token>`；
- Token 在 Sink 的「系统管理」或「端管理」中生成/轮换，轮换后旧 Token 立即失效；
- 控制台可为每个 Sink 端保存独立 Token；未配置时回退到 Source 部署级 `MIC_SYNC_SINK_TOKEN`；
- 有状态请求必须携带 `X-Mic-Expected-Sink-Instance-Id`，身份不匹配以 HTTP 409 拒绝且不写业务数据。

## 传输安全

- 支持 HTTP 与 HTTPS；生产建议使用 HTTPS；
- 默认校验证书链与目标主机名，失败时不自动降级；
- `tls.insecureSkipVerify=true` 可显式跳过校验，仅适用于受控内网并持续显示风险；
- P0 使用 JDK 标准 truststore，不提供应用级自定义 truststore 生命周期管理。

## 数据加密

- 数据库连接密码与 Sink Token 使用 AES-GCM 加密后存入本地 SQLite；
- Master Key 首次启动生成在 `${dataDir}/secret/master.key`，也可通过 `MIC_SYNC_MASTER_KEY` 外部注入；
- Spool 批次文件使用 Master Key 加密，`.part` 写入完成后 fsync 并原子改名；
- Gzip 批次的 Hash 基于压缩后的传输字节，Spool 磁盘内容保持加密。

## 最小权限

```text
Source：源对象 SELECT + 元数据读取
Sink 业务表：INSERT、UPDATE
Sink 回执：SELECT、INSERT
```

- Sink 无 DDL 权限时由 DBA 执行回执表初始化 SQL；
- 工具不会自动创建、修改或删除目标业务表，不传播 DELETE；
- 数据库账号细节见 `docs/operations/database-accounts.md`。

## 威胁与缓解

| 威胁 | 缓解 |
|---|---|
| 未授权访问管理台 | 管理员 Session + CSRF，仅本机或内网暴露 |
| Token 泄露 | 按端配置、可轮换、UI 只显示掩码 |
| 批次重放 | 回执同事务 + Payload Hash 冲突拒绝 |
| Spool 篡改 | AES-GCM 加密 + 原子写入 + 大小/Hash 校验 |
| 错误路由到其他 Sink | `sinkInstanceId` 绑定与 `X-Mic-Expected-Sink-Instance-Id` 校验 |
| 结构漂移 | 每次 Run Preflight，不兼容变化暂停 |

## P1 边界

目标数据库 Epoch/回退检测、Sink 身份重绑定、回执自动清理、应用级备份恢复、自定义 truststore 生命周期和专用最终切换向导均属于 P1，当前版本不提供，详见 `docs/help/support-scope.md`。
