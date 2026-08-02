# 数据库账号与权限手册

> 最近更新：2026-08-02

## Source（读取端）账号

最小权限（数据同步工具只读源数据）：

- `SELECT`：任务读取表/视图；
- 元数据读取：`pg_catalog` / `information_schema` 查询（JDBC 元数据）；
- **不需要**任何写权限。

## Sink（目标端）账号

业务写入 + 回执（工具自动创建回执表时额外需要 DDL）：

| 能力 | 是否必需 | 说明 |
|---|---|---|
| `SELECT` | 是 | 回执查询、目标元数据、可选目标行数探查 |
| `INSERT` / `UPDATE` | 是 | 业务 UPSERT |
| `CREATE TABLE` | 可选 | 回执表自动创建；无 DDL 权限时由 DBA 执行初始化 SQL |
| `DELETE` | 否 | MVP 不删除目标数据 |

## 回执表初始化（无 DDL 权限场景）

Sink 未就绪时，管理界面会展示 DBA SQL；SQL 模板由服务内置资源提供
（`db/target/{kingbase,opengauss}/create_receipt.sql`），也可直接使用：

```sql
-- KingbaseES / openGauss（内容相同，PG 兼容）
CREATE TABLE IF NOT EXISTS mic_sync_batch_receipt (
    batch_id           VARCHAR(64)  NOT NULL,
    source_instance_id VARCHAR(64)  NOT NULL,
    task_id            VARCHAR(64)  NOT NULL,
    run_id             VARCHAR(64)  NOT NULL,
    batch_sequence     BIGINT       NOT NULL,
    payload_hash       VARCHAR(128) NOT NULL,
    received_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_instance_id, batch_id)
);
```

执行后刷新 Sink Readiness 即可开放接收。

## 多数据源说明

- 「数据源」按所属端维护：本地端数据源保存在控制台本地并加密存储；远程 Sink 端的数据源由控制台通过 Agent API 下发到所属端保存，控制台只保留目录镜像，不保存远程密码；
- 无论数据源归属本地还是远程端，账号最小权限要求一致：Source 只读、Sink 业务写入 + 回执读写；
- 被任务引用的数据源不能删除。

## 驱动与验证

- KingbaseES 驱动类：`com.kingbase8.Driver`（JAR 前缀 `kingbase8-`）；
- openGauss 驱动类：`org.opengauss.Driver`（JAR 前缀 `opengauss-jdbc-`）；
- 在 Web UI「数据源」中测试连接，返回产品名/版本/SELECT/事务能力。
