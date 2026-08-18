# 测试指南

## 统一验证

```bash
./scripts/verify.sh
```

包含后端 `mvn verify`、前端 lint/type-check/单测/构建，以及前端 Playwright e2e。

## 后端单测

```bash
./mvnw -pl server verify
```

## 前端单测

```bash
npm --prefix web run test -- --run
```

覆盖导航、登录、端管理、数据源、任务向导、运行列表/详情、状态映射等核心视图。

后端新增覆盖：目标元数据远程查询、CSRF Cookie 名可配置、驱动加载、无时区时间语义、
双阶段增量游标、UPSERT_NO_OVERWRITE / REPLACE_ALL 写入模式与批次退避重试等。

## 真实数据库 E2E

完整说明见源码仓库中的 `e2e/README.md`（分发包不含 e2e 测试脚本）。核心脚本在源码仓库根目录执行：

```bash
# 三方向：KingbaseES→openGauss / openGauss→openGauss / openGauss→KingbaseES
./e2e/scripts/run-direction-tests.sh opengauss-to-opengauss

# 故障注入：回执不可达、错误 Token、重启恢复等
./e2e/scripts/run-failure-tests.sh

# 容量基准：10 万全量 + 1 万增量 + 稳定性观察
./e2e/scripts/run-capacity-smoke.sh
```

需要配置真实数据库连接环境变量：

```text
E2E_SOURCE_URL / E2E_SOURCE_USER / E2E_SOURCE_PASSWORD
E2E_TARGET_URL / E2E_TARGET_USER / E2E_TARGET_PASSWORD
E2E_DRIVERS_DIR
```

结果输出到 `e2e/results/`：

- `direction-<name>.json`
- `failure-tests.json`
- `capacity-smoke.json`

## 冒烟测试

```bash
./scripts/smoke-test.sh
```

启动打包后的 JAR 到临时数据目录，验证 readiness、`/api/v1/system/ping` 与 SPA 首页。

## 覆盖矩阵

| 场景 | 入口 |
|---|---|
| Table 全量/增量 | run-direction-tests.sh |
| SQL 模式 | run-direction-tests.sh |
| 发送前断网/响应丢失/重启/Hash 冲突 | run-failure-tests.sh |
| 手动增量续采与游标时间基准 | 后端 IncrementalCursorIntegrationTest（门控） |
| 10 万行容量与稳定性 | run-capacity-smoke.sh |
| 管理台交互 | web Playwright e2e |
