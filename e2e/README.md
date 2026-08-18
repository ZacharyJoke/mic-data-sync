# E2E 测试（三方向、故障注入、容量基准）

## 环境要求

- 真实 KingbaseES 与 openGauss 数据库各一个（Source/Target 可复用同一实例不同 Schema）；
- 数据库账号具备 DDL（建表）、SELECT、INSERT/UPDATE 权限；
- 已构建的可执行 JAR（`server/target/mic-data-sync-server-0.1.0-SNAPSHOT.jar`）；
- JDK 21；
- 数据库 JDBC 驱动 JAR 放置在客户端 `${dataDir}/drivers`（kingbase8-*.jar / opengauss-jdbc-*.jar；
  分发包/镜像已内置 opengauss-jdbc-3.0.0.jar 与 kingbase8-8.2.0.jar）。

## 运行方式（验收环境）

```bash
# 1. 初始化夹具（分别对 Source/Target 执行对应产品 SQL）
psql -f e2e/sql/kingbase/create_source_tables.sql
psql -f e2e/sql/kingbase/create_target_tables.sql
psql -f e2e/sql/opengauss/create_source_tables.sql
psql -f e2e/sql/opengauss/create_target_tables.sql

# 2. 生成数据（Source 侧）
psql -v rows=100000 -f e2e/fixtures/patient-data-generator.sql

# 3. 三方向 E2E（需配置环境变量，见脚本内说明）
./e2e/scripts/run-direction-tests.sh kingbase-to-opengauss
./e2e/scripts/run-direction-tests.sh opengauss-to-opengauss
./e2e/scripts/run-direction-tests.sh opengauss-to-kingbase

# 4. 故障注入
./e2e/scripts/run-failure-tests.sh

# 5. 容量基准
./e2e/scripts/run-capacity-smoke.sh
```

## 覆盖矩阵

| 场景 | 脚本/断言 |
|---|---|
| KingbaseES→openGauss | run-direction-tests.sh |
| openGauss→openGauss | run-direction-tests.sh |
| openGauss→KingbaseES | run-direction-tests.sh |
| Table 全量/增量 | 行数断言 + 更新时间字段校验 |
| SQL 模式 | 至少 KingbaseES Source 与 openGauss Source 各一次 |
| 全量期间新增/更新/同时间戳多行/10 分钟回看 | 增量断言 |
| 发送前断网/响应丢失/Source 重启/Sink 重启/Hash 冲突 | run-failure-tests.sh |
| LONGTEXT 1 MiB / NULL 游标 / 唯一约束缺失 | 故障与负向用例 |
| 10 万全量 + 1 万增量（主方向） | run-capacity-smoke.sh |
| 4 小时稳定性 | 观察内存与 Spool 目录增长 |

## 结果输出

脚本输出到 `e2e/results/`（自动创建）：

- `direction-<name>.json`：每方向通过/失败、行数、耗时；
- `failure-<name>.json`：故障注入结果与观察到的错误码/requestId；
- `capacity-smoke.json`：容量基准统计。

失败时脚本退出非 0 并输出稳定错误码、日志 requestId 与可复现命令。
