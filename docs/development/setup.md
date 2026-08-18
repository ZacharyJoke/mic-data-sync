# 开发环境搭建

## 环境要求

- JDK 21（LTS）
- Node.js 20+（当前开发基线 22）
- npm 10+
- 可选：Docker（本地测试数据库）

Maven 使用项目自带的 Wrapper（`./mvnw`），不需要单独安装。

## 目录结构

```text
server/              Spring Boot 后端（唯一 Maven 业务模块）
web/                 Vue 3 + Element Plus 管理台
scripts/             开发、验证、构建、冒烟脚本
e2e/                 三方向真实数据库端到端测试
distribution/        分发包源文件
docs/                文档
```

## 启动开发环境

后端（默认端口 19090）：

```bash
export MIC_SYNC_ADMIN_PASSWORD='<强密码>'
./scripts/dev-server.sh
```

前端（Vite，代理 `/api` 与 `/actuator` 到本地后端）：

```bash
./scripts/dev-web.sh
```

打开 Vite 输出的本地地址完成开发联调。

## 常用命令

```bash
# 后端单测与打包
./mvnw -pl server verify

# 前端 lint
npm --prefix web run lint

# 前端类型检查
npm --prefix web run type-check

# 前端单测
npm --prefix web run test -- --run

# 前端构建
npm --prefix web run build

# 前端 Playwright e2e
npm --prefix web run test:e2e

# 统一验证门禁（上述全部）
./scripts/verify.sh
```

## 数据库驱动

分发包默认内置 openGauss / PostgreSQL 驱动；开发时如需替换或补充，把 JDBC 驱动 JAR
放入 `${MIC_SYNC_DATA_DIR}/drivers`：

- KingbaseES：`kingbase8-8.6.0.jar`（驱动类 `com.kingbase8.Driver`，商业授权需自备）；
- openGauss：`opengauss-jdbc-3.0.0.jar`（驱动类 `org.opengauss.Driver`）；
- openGauss 兼容库（如 Vastbase）：`postgresql-42.7.13.jar`（驱动类 `org.postgresql.Driver`）。

## 提交前检查

- 运行 `./scripts/verify.sh`；
- 同步更新受影响的文档（能力边界、错误码、配置说明）；
- 不要提交 `data/`、`.env`、构建产物与内部验证记录。
