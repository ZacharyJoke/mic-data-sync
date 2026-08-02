-- 端注册表：控制台纳管的 Source/Sink 实例
-- v1 约束：Source 端固定为当前实例（self-source）；Sink 端可注册多个（本地 + 远程）
CREATE TABLE sync_endpoint (
    id                  TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    role                TEXT NOT NULL CHECK (role IN ('SOURCE', 'SINK')),
    base_url            TEXT,
    instance_id         TEXT,
    management_token_enc TEXT,
    is_self             INTEGER NOT NULL DEFAULT 0 CHECK (is_self IN (0, 1)),
    status              TEXT NOT NULL DEFAULT 'UNKNOWN',
    last_probe_at       TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    UNIQUE (role, name)
);
CREATE INDEX idx_sync_endpoint_role ON sync_endpoint (role);

INSERT INTO sync_endpoint
    (id, name, role, base_url, instance_id, management_token_enc, is_self, status, last_probe_at, created_at, updated_at)
VALUES
    ('self-source', '本地 Source 端（自己）', 'SOURCE', NULL, NULL, NULL, 1, 'UNKNOWN', NULL,
     strftime('%Y-%m-%dT%H:%M:%SZ', 'now'), strftime('%Y-%m-%dT%H:%M:%SZ', 'now')),
    ('self-sink', '本地 Sink 端', 'SINK', NULL, NULL, NULL, 1, 'UNKNOWN', NULL,
     strftime('%Y-%m-%dT%H:%M:%SZ', 'now'), strftime('%Y-%m-%dT%H:%M:%SZ', 'now'));

-- 数据源档案：归属于具体端（本地 Source / 本地 Sink / 远程 Sink）
CREATE TABLE data_source (
    id           TEXT PRIMARY KEY,
    endpoint_id  TEXT NOT NULL REFERENCES sync_endpoint (id),
    name         TEXT NOT NULL,
    product      TEXT NOT NULL,
    jdbc_url     TEXT NOT NULL,
    username     TEXT NOT NULL,
    password_enc TEXT NOT NULL,
    driver_type  TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL,
    UNIQUE (endpoint_id, name)
);
CREATE INDEX idx_data_source_endpoint ON data_source (endpoint_id);

-- 把既有单连接配置迁移为本地默认档案（保持旧任务可用）
INSERT INTO data_source (id, endpoint_id, name, product, jdbc_url, username, password_enc, driver_type, created_at, updated_at)
SELECT 'source-default', 'self-source', '默认 Source 数据源', product, jdbc_url, username, password_enc, driver_type, created_at, updated_at
FROM role_database_config WHERE role = 'SOURCE';

INSERT INTO data_source (id, endpoint_id, name, product, jdbc_url, username, password_enc, driver_type, created_at, updated_at)
SELECT 'sink-default', 'self-sink', '默认 Sink 数据源', product, jdbc_url, username, password_enc, driver_type, created_at, updated_at
FROM role_database_config WHERE role = 'SINK';

-- 任务绑定：Source 固定为当前实例；Sink 端与目标数据源由控制台解析
ALTER TABLE task ADD COLUMN source_endpoint_id TEXT;
ALTER TABLE task ADD COLUMN sink_endpoint_id TEXT;
ALTER TABLE task ADD COLUMN source_data_source_id TEXT;
ALTER TABLE task ADD COLUMN target_data_source_id TEXT;

UPDATE task SET source_endpoint_id = 'self-source' WHERE source_endpoint_id IS NULL;
UPDATE task SET source_data_source_id = 'source-default'
WHERE source_data_source_id IS NULL AND EXISTS (SELECT 1 FROM data_source WHERE id = 'source-default');
UPDATE task SET target_data_source_id = 'sink-default'
WHERE target_data_source_id IS NULL AND EXISTS (SELECT 1 FROM data_source WHERE id = 'sink-default');

-- 管理令牌：控制台调用本实例 Agent API 时使用（单行，加密存储）
CREATE TABLE management_token (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    token_enc     TEXT NOT NULL,
    token_display TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

DROP TABLE role_database_config;
