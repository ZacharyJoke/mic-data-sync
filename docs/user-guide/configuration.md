# 配置说明

## 配置优先级

应用按以下顺序读取配置（后者覆盖前者）：

```text
application.yml < 环境变量 < 启动脚本命令行参数
```

`distribution/config/application-example.yml` 是完整的配置模板，复制后按需修改。

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MIC_SYNC_SERVER_PORT` | `19090` | HTTP 服务端口 |
| `MIC_SYNC_CONTEXT_PATH` | `/mic-data-sync` | URL 前缀（Spring context-path），开发/测试/生产统一 |
| `MIC_SYNC_ROLES` | `source,sink` | 部署角色：`source` / `sink` / `source,sink` |
| `MIC_SYNC_DATA_DIR` | `./data` | 数据目录（SQLite、Master Key、Spool、日志） |
| `MIC_SYNC_LOG_DIR` | `${dataDir}/logs` | 应用日志目录 |
| `MIC_SYNC_DRIVER_DIR` | `${dataDir}/drivers` | JDBC 驱动目录（分发包已内置驱动时可不配置） |
| `MIC_SYNC_TIMEZONE` | `Asia/Shanghai` | 工作台统计使用的本地时区 |
| `MIC_SYNC_ADMIN_PASSWORD` | 无 | 首次启动初始化管理员 |
| `MIC_SYNC_MASTER_KEY` | 自动生成 | Base64 编码 32 字节 Master Key |
| `MIC_SYNC_SINK_TOKEN` | 无 | Source 访问 Sink 的全局令牌，可在 UI 按端覆盖 |
| `MIC_SYNC_SESSION_COOKIE` | `JSESSIONID` | 会话 Cookie 名；同主机多实例必须配置不同值，避免互顶 |
| `MIC_SYNC_CSRF_COOKIE` | `XSRF-TOKEN` | CSRF Cookie 名；同主机多实例必须配置不同值 |
| `MIC_SYNC_SOURCE_MAX_TASKS` | `10` | Source 最大任务数 |
| `MIC_SYNC_SOURCE_MAX_ACTIVE_RUNS` | `1` | 全局最大活动 Run |
| `MIC_SYNC_SINK_MAX_ROWS_PER_BATCH` | `1000` | 单批次最大行数 |
| `MIC_SYNC_SINK_MAX_PAYLOAD_BYTES` | `16777216` | 单批次解压后最大负载字节 |
| `MIC_SYNC_SINK_TLS_INSECURE_SKIP_VERIFY` | `false` | 显式跳过 HTTPS 证书校验（高风险） |

## 角色

- `source`：只启用读取与发送能力；
- `sink`：只启用接收与写入能力；
- `source,sink`：双角色，本机任务使用内部调用。

角色修改后需要重启生效。

## 数据目录

```text
data/
├── mic-data-sync.db      # 本地 SQLite 状态库
├── secret/master.key     # AES-GCM Master Key
├── spool/                # 加密批次 Spool
├── logs/app.log          # 按天滚动日志（保留 14 天）
└── drivers/              # 数据库 JDBC 驱动目录
```

`drivers/` 首次启动自动创建。分发包默认内置 openGauss / PostgreSQL 驱动；
KingbaseES 驱动为商业授权组件，需按许可放入 `drivers/` 后重启（详见
[驱动放置说明](../../distribution/drivers/README.md)）。

## 安全配置建议

- 生产环境通过 `EnvironmentFile` 或密钥管理系统注入管理员密码、Master Key 与 Sink Token；
- 不要把这些敏感值写入 YAML 并提交到版本库；
- HTTPS 场景先修复证书链或配置受信 truststore，不要默认开启 `tlsInsecureSkipVerify`。
