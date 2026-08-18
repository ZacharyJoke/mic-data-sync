-- 扩展 task.write_mode 枚举：新增 UPSERT_NO_OVERWRITE（冲突跳过，保留目标行）
-- SQLite 无法修改 CHECK 约束，需重建 task 表（run/checkpoint 外键引用 task_id，
-- 重建期间临时关闭外键校验。sqlite-jdbc 默认 foreign_keys=OFF，迁移结束后保持
-- OFF（与原默认一致），不在末尾重新开启，避免改变连接池中该连接的外键行为。

PRAGMA foreign_keys = OFF;

CREATE TABLE task_new (
    task_id          TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    version          INTEGER NOT NULL DEFAULT 1,
    lifecycle_status TEXT NOT NULL CHECK (lifecycle_status IN
        ('DRAFT', 'ENABLED', 'PAUSED', 'DISABLED', 'BLOCKED', 'DELETING', 'DELETED')),
    read_mode        TEXT NOT NULL CHECK (read_mode IN ('TABLE', 'SQL')),
    read_definition  TEXT NOT NULL,
    target_schema    TEXT,
    target_table     TEXT NOT NULL,
    write_mode       TEXT NOT NULL CHECK (write_mode IN ('UPSERT', 'UPSERT_NO_OVERWRITE', 'INSERT_ONLY')),
    unique_keys      TEXT,
    field_mappings   TEXT NOT NULL,
    remote_sink_url  TEXT,
    sink_token_ref   TEXT,
    expected_sink_instance_id TEXT,
    source_endpoint_id  TEXT,
    sink_endpoint_id    TEXT,
    source_data_source_id TEXT,
    target_data_source_id TEXT,
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL
);

INSERT INTO task_new (task_id, name, version, lifecycle_status, read_mode, read_definition,
    target_schema, target_table, write_mode, unique_keys, field_mappings,
    remote_sink_url, sink_token_ref, expected_sink_instance_id,
    source_endpoint_id, sink_endpoint_id, source_data_source_id, target_data_source_id,
    created_at, updated_at)
SELECT task_id, name, version, lifecycle_status, read_mode, read_definition,
    target_schema, target_table, write_mode, unique_keys, field_mappings,
    remote_sink_url, sink_token_ref, expected_sink_instance_id,
    source_endpoint_id, sink_endpoint_id, source_data_source_id, target_data_source_id,
    created_at, updated_at
FROM task;

DROP TABLE task;
ALTER TABLE task_new RENAME TO task;

CREATE INDEX idx_task_name ON task (name);
CREATE INDEX idx_task_lifecycle_status ON task (lifecycle_status);
CREATE INDEX idx_task_created_at ON task (created_at);
