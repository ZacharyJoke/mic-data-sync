# 安装部署手册（Linux x86_64）

> 最近更新：2026-08-18

## 部署方式

本手册覆盖裸机/虚拟机 + systemd 的安装方式。容器化部署与反向代理说明见仓库
`distribution/` 下的 nginx 配置样例；三种方式的数据目录语义一致。

## 环境要求

- 操作系统：Linux x86_64（Kylin/银河麒麟 V10 等兼容发行版已验证基线）
- JDK：**Java 21**（由操作系统提供，应用包不捆绑 JDK）
- 磁盘：dataDir 所在分区预留至少 2× 预期 Spool 容量

## 安装步骤

1. 创建安装目录并解压分发包：

```bash
sudo mkdir -p /opt/mic-data-sync
sudo tar -xzf mic-data-sync-0.1.0-linux-x86_64.tar.gz -C /opt/mic-data-sync
```

2. 确认 Java 21：

```bash
java -version   # 需为 21
```

3. 数据库驱动：分发包默认内置 `opengauss-jdbc-3.0.0.jar` 与
   `postgresql-42.7.13.jar`（Vastbase 等 openGauss 兼容库）；KingbaseES 驱动为
   商业授权组件，需将 `kingbase8-8.6.0.jar` 放入
   `${MIC_SYNC_DRIVER_DIR}`（默认 `/opt/mic-data-sync/drivers`；首次启动会自动
   创建该目录并生成说明文件）。

4. 启动（角色默认 `source,sink`）：

```bash
export MIC_SYNC_ADMIN_PASSWORD='<强密码>'   # 首次启动初始化管理员
/opt/mic-data-sync/bin/start.sh
```

5. 验证：

```bash
curl http://127.0.0.1:19090/actuator/health/readiness
```

应用统一使用 `/mic-data-sync/` URL 前缀，完整就绪地址为
`http://127.0.0.1:19090/mic-data-sync/actuator/health/readiness`。

## 常用配置（环境变量）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `MIC_SYNC_SERVER_PORT` | `19090` | HTTP 端口 |
| `MIC_SYNC_CONTEXT_PATH` | `/mic-data-sync` | URL 前缀（统一） |
| `MIC_SYNC_ROLES` | `source,sink` | 部署角色：`source` / `sink` / `source,sink` |
| `MIC_SYNC_DATA_DIR` | `./data` | 数据目录（SQLite、Master Key、Spool、日志） |
| `MIC_SYNC_LOG_DIR` | `${dataDir}/logs` | 应用日志目录 |
| `MIC_SYNC_DRIVER_DIR` | `${dataDir}/drivers` | JDBC 驱动目录 |
| `MIC_SYNC_ADMIN_PASSWORD` | 无 | 首次启动初始化管理员 |
| `MIC_SYNC_MASTER_KEY` | 自动生成 | Base64 编码 32 字节 Master Key |
| `MIC_SYNC_SINK_TOKEN` | 无 | Source 访问 Sink 的全局令牌，可在 Web UI 按端覆盖 |
| `MIC_SYNC_SOURCE_MAX_TASKS` | `10` | Source 最大任务数 |
| `MIC_SYNC_SOURCE_MAX_ACTIVE_RUNS` | `1` | 全局最大活动 Run |
| `MIC_SYNC_SESSION_COOKIE` / `MIC_SYNC_CSRF_COOKIE` | `JSESSIONID` / `XSRF-TOKEN` | 同主机多实例必须配置不同值 |

## systemd 托管

```bash
sudo cp /opt/mic-data-sync/systemd/mic-data-sync.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mic-data-sync
```

建议通过 `EnvironmentFile` 提供敏感配置（管理员密码、Master Key、Sink Token）。

## 停止与重启

```bash
/opt/mic-data-sync/bin/stop.sh          # 停止
/opt/mic-data-sync/bin/start.sh         # 重启
```

日志写入 `${MIC_SYNC_DATA_DIR}/logs/app.log`（按天滚动，保留 14 天）。

## 升级

1. 停止服务；
2. 备份 `data/`（含 SQLite、`secret/master.key`、Spool）；
3. 替换 JAR 与脚本；
4. 启动并核对 `data/secret/master.key` 未变化（否则加密配置无法解密）；
5. 确认健康检查与 Sink Readiness。

## 账号建议

- 建议使用独立系统账号（如 `mic`）运行，`root` 可运行但更易扩大误操作影响面；
- systemd 样例默认 `User=mic`，安装前请创建该账号或按环境修改；
- 数据库账号遵循[数据库账号与权限手册](database-accounts.md)。
