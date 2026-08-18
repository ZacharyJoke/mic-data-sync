# 测试数据库 Docker 部署指引（openGauss / KingbaseES）

> 最近更新：2026-08-02  
> 适用：x86_64 Linux 服务器（示例地址 192.0.2.10），Docker 20+。
> 已实测服务器可访问 Docker Hub；openGauss 官方镜像可直接拉取。
> 本文为测试环境记录，包含具体测试服务器与连接参数，生产部署以[安装部署手册](installation.md)为准。

## 一、openGauss（官方镜像）

### 1. 完整拉取镜像（若之前中断，先删干净）

```bash
docker rm -f mic-sync-opengauss 2>/dev/null || true
docker rmi opengauss/opengauss:latest 2>/dev/null || true
docker pull opengauss/opengauss:latest
```

> 必须等 `docker pull` 输出 `Status: Downloaded newer image` 才算完整。
> 若中途中断（`Pulling fs layer` 卡住），镜像层会损坏，启动时报
> `gaussdb is needed by gs_initdb`——此时删除镜像重新完整拉取即可。

### 2. 启动容器（端口 15432 → 容器 5432）

```bash
docker run -d --name mic-sync-opengauss \
  -p 15432:5432 \
  -e GS_PASSWORD='ChangeMe@123' \
  -e GS_USERNAME=sync_user \
  -e GS_DBNAME=sync_db \
  opengauss/opengauss:5.0.0
```

环境变量说明：

| 变量 | 值 | 说明 |
|---|---|---|
| `GS_PASSWORD` | `ChangeMe@123` | 必填；openGauss 密码复杂度要求：至少 8 位，含大写/小写/数字 |
| `GS_USERNAME` | `gaussdb` | 默认超级用户（默认 gaussdb） |
| `GS_DBNAME` | `mic_sync` | 默认数据库（默认 postgres） |

### 3. 等待初始化并验证

首次启动 openGauss 会执行 initdb，约 30~60 秒：

```bash
# 观察初始化日志（出现 "The files belonging to this database system" 后等待就绪）
docker logs -f mic-sync-opengauss

# 端口确认
ss -ltn | grep 15432

# 进入容器执行 SQL 验证
docker exec -it mic-sync-opengauss bash -c \
  "gsql -U gaussdb -d mic_sync -c 'select version();'"
```

### 4. 连接信息（供 mic-data-sync 配置）

```text
JDBC URL:  jdbc:opengauss://<服务器IP>:15432/mic_sync
用户名:    gaussdb
密码:      ChangeMe@123
驱动 JAR:  opengauss-jdbc-3.0.0.jar（分发包默认内置；开发环境放到 data/drivers）
```

## 二、KingbaseES（需授权镜像）

> **官方说明**：人大金仓 KingbaseES 不提供公开 Docker Hub 镜像，
> 需从[金仓官网](https://www.kingbase.com.cn)下载（开发版/企业版，需注册授权）。
> 常见的可行方式：官网下载安装包（rpm/tar）、或从内网 Harbor 获取已授权镜像。

### 方案 A：内网 Harbor 镜像（推荐，若有）

```bash
# 1. 拉取镜像（以实际 Harbor 地址为例）
docker pull harbor.example.com/gd-mic/kingbase:v8

# 2. 启动（启动参数以镜像 README 为准，通用示例）
docker run -d --name mic-sync-kingbase \
  -p 15431:54321 \
  -e DB_USER=system \
  -e DB_PASSWORD='ChangeMe@123' \
  harbor.example.com/gd-mic/kingbase:v8

# 3. 验证
docker exec -it mic-sync-kingbase bash -c "ksql -U system -d test -c 'select version();'"
```

### 方案 B：镜像 tar 包导入

```bash
# 1. 将官方/授权渠道获取的镜像包上传到服务器
docker load -i kingbase-v8.tar
docker images | grep -i kingbase

# 2. 用镜像的 REPOSITORY:TAG 启动（参数参考方案 A）
```

### 方案 C：二进制安装（无镜像时）

- 官网下载 KingbaseES V8 Linux x86_64 安装包（如 `KingbaseES_V008R006C008...linux-x86_64.tar.bz2`）；
- 解压后执行 `install.sh`（setup 模式，可选安装目录 `/opt/kingbase`）；
- 初始化实例：`/opt/kingbase/bin/initdb -D /opt/kingbase/data -U system -W`；
- 启动：`/opt/kingbase/bin/pg_ctl -D /opt/kingbase/data -l /opt/kingbase/log.log start`；
- 默认端口 **54321**。

### 连接信息（KingbaseES 通用）

```text
JDBC URL:  jdbc:kingbase8://<服务器IP>:54321/<dbname>
用户名:    system（默认超级用户）
驱动 JAR:  kingbase8-8.6.0.jar（商业授权，需自行获取；分发包内置 openGauss/PostgreSQL 驱动，KingbaseES 需放入 data/drivers）
```

## 三、安全提示

- 测试库密码使用强密码，避免使用弱口令；
- 如需对外暴露，建议仅对测试客户端 IP 开放端口（云安全组/防火墙）；
- 测试完成后可停止容器：`docker rm -f mic-sync-opengauss`。


## 四、openGauss 5.0 实测注意事项（2026-08-01 已验证）

- **初始用户禁止远程连接**：`opengauss`（默认超级用户）只能本地 socket 连接，
  JDBC 远程连接报 `FATAL: Forbid remote connection with initial user`。
  需用 `GS_USERNAME` 创建的用户（如 `sync_user`）远程连接；
  若该用户非超级用户，用初始用户执行 `ALTER USER sync_user SYSADMIN;` 提升。
- **默认数据库**：镜像初始化创建的是 `opengauss` 库（`GS_DBNAME` 不生效），
  需手动 `CREATE DATABASE mic_sync;`。
- **驱动版本**：`opengauss-jdbc:5.0.0` 主类为 `org.postgresql.Driver`（不识别
  `jdbc:opengauss:` URL）；`opengauss-jdbc:3.0.0` 主类为 `org.opengauss.Driver` 可用。
  客户端已支持两类候选驱动自动加载。
- **UPSERT 语法**：openGauss **不支持** PostgreSQL 的 `INSERT ... ON CONFLICT`，
  需使用 `INSERT ... ON DUPLICATE KEY UPDATE col = EXCLUDED.col`（客户端已按方言适配）。
- **时间格式**：openGauss `CURRENT_TIMESTAMP` 返回 `yyyy-MM-dd HH:mm:ss.SSSSSS+08`，
  客户端已兼容解析。
- **客户端工具**：容器内 `gsql` 需设置 `LD_LIBRARY_PATH=/usr/local/opengauss/lib`，
  密码用 `-W` 参数（非 `PGPASSWORD`）。


### Navicat 等 PG 客户端连接（md5 认证兼容）

openGauss 默认 `sha256`/`sm3` 认证为自定义算法，Navicat 报
`none of the server's SASL authentication mechanisms are supported`。已调整为兼容方案：

```bash
# 示例：password_encryption_type=1（sha256+md5）、pg_hba 外部规则 md5、重置用户密码
# 连接参数（按实际环境替换）
#   主机: <服务器IP>  端口: 15432
#   用户: <数据库用户>  密码: <强密码>
#   数据库: <数据库名>
```

验证：`SELECT 1;` 可执行；mic-data-sync 客户端 JDBC 连接正常（md5 认证）。

### 服务日志

- 日志文件：`${MIC_SYNC_DATA_DIR}/logs/app.log`（按天滚动，保留 14 天）；
- 部署后启动日志与运行诊断统一写入该文件，便于排查启动失败与同步问题。
