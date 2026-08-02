# 贡献指南

欢迎为 mic-data-sync 提交贡献。本指南帮助新贡献者快速理解开发流程、质量门禁和文档约定。

## 行为准则

所有参与者必须遵守[行为准则](CODE_OF_CONDUCT.md)。

## 开发环境

- JDK 21（LTS）
- Node.js 20+（当前开发基线 22）
- npm 10+
- Maven 由项目自带的 `./mvnw` 提供，无需单独安装

## 本地开发

```bash
# 后端开发模式（默认端口 19090）
./scripts/dev-server.sh

# 前端开发模式（Vite，代理 /api 与 /actuator 到本地后端）
./scripts/dev-web.sh

# 统一验证门禁
./scripts/verify.sh
```

首次启动后端前请设置管理员密码：

```bash
export MIC_SYNC_ADMIN_PASSWORD='<强密码>'
```

## 提交规范

- 提交信息使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式；
- 常用类型：`feat`、`fix`、`docs`、`test`、`refactor`、`chore`、`build`；
- 中文提交说明可以同时包含英文类型前缀，例如 `docs: 补充开源文档`；
- 一次提交只解决一个问题，避免混合无关改动。

## 测试门禁

任何代码改动都必须通过 `./scripts/verify.sh`，包含：

1. 后端 `mvn verify`（单测与打包）；
2. 前端 `lint`、`type-check`、单测、构建；
3. 前端 Playwright e2e。

涉及真实数据库的行为变更需要补充 `e2e/` 下的三方向测试或故障注入用例，并至少在本机验证可运行。

## 文档约定

- 新增或修改公开错误码时，必须同步更新 `server/src/main/java/com/mic/datasync/shared/error/ErrorCode.java` 与 `docs/help/error-codes.md`；
- 能力变化必须同步更新 `docs/help/support-scope.md`；
- 环境变量、配置项变化必须同步更新 `docs/user-guide/configuration.md` 与 `distribution/config/application-example.yml`；
- 公开文档不出现真实服务器地址、密码、Token、实例 ID 等敏感信息；测试环境记录放在 `docs/internal/`（该目录不随仓库发布）。

## 提交流程

1. Fork 仓库并基于最新 `main` 创建分支；
2. 完成改动并运行 `./scripts/verify.sh`；
3. 提交信息符合规范；
4. 创建 Pull Request，描述改动动机、验证方式和影响范围；
5. 维护者 review 通过后合并。

## 安全和漏洞报告

安全相关问题不要公开发 Issue。请通过 [SECURITY.md](SECURITY.md) 描述的方式私有披露。
