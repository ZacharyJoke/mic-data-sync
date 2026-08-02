# mic-data-sync

基于「Source 客户端 → Sink 客户端 → 目标数据库」两级架构的轻量数据同步工具（MVP-I1 候选版本 `0.1.0-SNAPSHOT`）。

- 客户端可通过配置同时启用 Source / Sink 角色，也可以只启用其中一个；
- 支持 KingbaseES（人大金仓）与 openGauss 之间三种方向的数据同步；
- 支持 Table 模式与单表 SQL 模式两种读取方式；
- 支持首次全量、自动追赶与手动增量，按批次回执安全续跑；
- Web 控制台内置于客户端，提供端管理、多数据源、任务配置、运行监控与故障诊断。

## 当前状态

- MVP 1.0 范围已冻结，MVP-I1 候选版本已交付（`0.1.0-SNAPSHOT`）；
- 已实现管理员登录、端管理、多数据源、Table/SQL 任务、预检启用、首次全量/手动增量、暂停/继续/安全重试、Sink Token 轮换与批次认证；
- Linux x86_64 分发包与 systemd 托管样例已提供。

## 文档

- [项目文档导航](docs/README.md)
- [快速开始](docs/user-guide/quick-start.md)
- [能力边界](docs/help/support-scope.md)
- [公开错误码](docs/help/error-codes.md)
- [REST API 参考](docs/api/api-reference.md)
- [架构总览](docs/architecture/overview.md)
- [路线图](docs/roadmap.md)
- [安装部署手册](docs/operations/installation.md)
- [首次同步操作手册](docs/operations/first-sync-runbook.md)

## 开源与社区

- [LICENSE](LICENSE)：MIT
- [贡献指南](CONTRIBUTING.md)
- [安全策略](SECURITY.md)
- [行为准则](CODE_OF_CONDUCT.md)
- [变更记录](CHANGELOG.md)
- [第三方许可证声明](docs/licensing.md)

## 环境要求

| 组件 | 版本 |
|---|---|
| JDK | 21（LTS） |
| Maven | 3.9+（构建时使用 Maven Wrapper） |
| Node.js | 20+（当前开发基线 22） |
| npm | 10+ |

## 仓库结构

```text
server/              Spring Boot 后端（唯一 Maven 业务模块，内置前端静态资源）
web/                 Vue 3 前端 SPA
scripts/             开发、验证、构建脚本
distribution/        分发包源文件（bin/config/systemd）
distribution-build/  构建产物（可执行 JAR、文档、Linux tar.gz）
e2e/                 三方向真实数据库端到端测试
docs/                帮助、错误码与运维文档
```

## 快速开始

```bash
# 后端开发模式（默认端口 19090）
./scripts/dev-server.sh

# 前端开发模式（Vite，代理 /api 与 /actuator 到本地后端）
./scripts/dev-web.sh

# 统一验证（后端测试/打包、前端 lint/type-check/test/build/e2e）
./scripts/verify.sh

# 构建分发包（web 产物打入 JAR，生成 distribution-build 与 Linux tar.gz）
./scripts/build-distribution.sh

# 冒烟测试（启动打包后的 JAR，验证 readiness、ping 与 SPA 首页）
./scripts/smoke-test.sh
```

首次启动需设置 `MIC_SYNC_ADMIN_PASSWORD` 初始化管理员；生产部署步骤见[安装部署手册](docs/operations/installation.md)。
