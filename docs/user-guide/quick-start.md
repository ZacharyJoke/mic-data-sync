# 快速开始

本指南用 Docker 里的 openGauss 作为源和目标数据库，最快跑通一次 Table 模式全量同步。完整部署请参考[安装部署手册](../operations/installation.md)。

## 前置条件

- JDK 21
- Node.js 20+（开发基线 22）与 npm 10+
- Docker 20+（用于启动测试数据库）
- 数据库 JDBC 驱动 JAR：
  - openGauss：分发包已内置 `opengauss-jdbc-3.0.0.jar`
  - KingbaseES：`kingbase8-8.6.0.jar`（商业授权，需从人大金仓获取后放入 `drivers/`）
  - openGauss 兼容库（如 Vastbase）：分发包已内置 `postgresql-42.7.13.jar`

## 1. 启动测试数据库

```bash
docker run -d --name mic-sync-opengauss \
  -p 15432:5432 \
  -e GS_PASSWORD='ChangeMe@123' \
  -e GS_USERNAME=sync_user \
  -e GS_DBNAME=sync_db \
  opengauss/opengauss:5.0.0
```

初始化完成后创建源表、目标表并写入示例数据。更多 openGauss/KingbaseES 细节见[测试数据库 Docker 部署](../operations/docker-databases.md)。

## 2. 构建并启动

```bash
./scripts/dev-server.sh
```

另开一个终端启动前端开发模式（可选，构建分发包时前端会打入 JAR）：

```bash
./scripts/dev-web.sh
```

首次启动前设置管理员密码：

```bash
export MIC_SYNC_ADMIN_PASSWORD='<强密码>'
```

打开 `http://127.0.0.1:19090/`，用 `admin` 和上述密码登录。

## 3. 配置端与数据源

1. 「端管理」确认本地 Source 端与本地 Sink 端已就绪（`READY`）；
2. 「数据源」分别保存 Source 与 Sink 数据源，点击「测试连接」；
3. 分发包已内置 openGauss / PostgreSQL 驱动；若使用 KingbaseES，把 `kingbase8-*.jar`
   放入 `${MIC_SYNC_DATA_DIR}/drivers` 后重启，驱动才会被加载。

## 4. 创建任务并同步

1. 「同步任务」→「新建任务」，选择 Source 数据源、Sink 端与目标数据源；
2. Table 模式选择源表、分页 Key（建议主键）与更新时间字段；
3. 填写目标表，加载目标字段并确认映射；
4. 「预检与提交」执行预检，无阻断项后保存并启用；
5. 任务详情点击「首次全量」（自动含追赶）；
6. 在目标数据库核对行数与内容，之后可随时点击「手动增量」。

## 下一步

- [配置说明](configuration.md)了解全部环境变量；
- [任务使用指南](tasks.md)了解 Table/SQL 模式与字段映射；
- [故障排查](troubleshooting.md)处理常见运行问题。
