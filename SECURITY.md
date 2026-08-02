# 安全策略

## 报告漏洞

请通过 GitHub 的 Security Advisory 功能提交私有漏洞报告，不要创建公开 Issue。

报告时请尽量包含：

- 受影响版本；
- 复现步骤或最小示例；
- 影响范围和潜在利用方式；
- 建议的修复方向（可选）。

维护者会在收到报告后尽快响应，并在修复完成并发布前对细节保密。

## 支持范围

- 当前支持：最新 `main` 分支与最新发布版本；
- 已停止维护的版本不提供安全修复；
- 本项目处于 MVP 阶段，安全边界以 `docs/help/support-scope.md` 为准。

## 安全设计要点

- 管理员登录使用 Cookie Session + CSRF 防护；
- Sink 数据接收使用 Bearer Token 认证，Token 可按端覆盖并在 UI 中轮换；
- 数据库连接密码与 Sink Token 使用 AES-GCM 加密存储或通过环境变量注入；
- Spool 文件使用 Master Key 加密并原子写入；
- 公开文档和仓库中不得出现真实服务器地址、密码、Token、密钥或生产实例 ID；
- 数据库账号遵循最小权限原则，详见 `docs/operations/database-accounts.md`。

## 不在安全范围内的事项

- 使用 `tls.insecureSkipVerify=true` 显式跳过 TLS 校验属于运维明确承担风险的配置，不代表传输安全；
- 数据库账号密码、Master Key 或 Sink Token 已泄露到公开渠道时，应立即重置并轮换，而不是仅删除提交记录。
