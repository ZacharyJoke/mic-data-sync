-- mic-data-sync 本地状态库初始结构（SQLite）
-- 时间统一使用 TEXT（ISO-8601 UTC）；UUID 统一使用 TEXT（36 字符）

-- 实例身份：一个 dataDir 生命周期内一条
CREATE TABLE client_instance (
    instance_id         TEXT PRIMARY KEY,
    application_version TEXT NOT NULL,
    protocol_version    INTEGER NOT NULL,
    roles               TEXT NOT NULL, -- SOURCE / SINK / SOURCE,SINK
    data_dir            TEXT NOT NULL,
    source_max_tasks    INTEGER NOT NULL DEFAULT 10,
    created_at          TEXT NOT NULL
);

-- 管理员账号
CREATE TABLE admin_user (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

-- 角色数据库连接配置：每个角色（SOURCE/SINK）最多绑定一个数据库
CREATE TABLE role_database_config (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    role         TEXT NOT NULL UNIQUE CHECK (role IN ('SOURCE', 'SINK')),
    product      TEXT NOT NULL, -- KINGBASE / OPENGAUSS
    jdbc_url     TEXT NOT NULL,
    username     TEXT NOT NULL,
    password_enc TEXT NOT NULL, -- 加密存储，明文不入库
    driver_type  TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);

-- 同步任务（复杂结构 read_definition / field_mappings / unique_keys 以 JSON 存储）
CREATE TABLE task (
    task_id          TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    version          INTEGER NOT NULL DEFAULT 1,
    lifecycle_status TEXT NOT NULL CHECK (lifecycle_status IN
        ('DRAFT', 'ENABLED', 'PAUSED', 'DISABLED', 'BLOCKED', 'DELETING', 'DELETED')),
    read_mode        TEXT NOT NULL CHECK (read_mode IN ('TABLE', 'SQL')),
    read_definition  TEXT NOT NULL, -- JSON
    target_schema    TEXT,
    target_table     TEXT NOT NULL,
    write_mode       TEXT NOT NULL CHECK (write_mode IN ('UPSERT', 'INSERT_ONLY')),
    unique_keys      TEXT,          -- JSON 数组，可空
    field_mappings   TEXT NOT NULL, -- JSON 数组
    remote_sink_url  TEXT,
    sink_token_ref   TEXT,
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL
);
CREATE INDEX idx_task_name ON task (name);
CREATE INDEX idx_task_lifecycle_status ON task (lifecycle_status);
CREATE INDEX idx_task_created_at ON task (created_at);

-- 同步运行
CREATE TABLE run (
    run_id               TEXT PRIMARY KEY,
    task_id              TEXT NOT NULL REFERENCES task (task_id),
    task_name_snapshot   TEXT NOT NULL,
    task_version         INTEGER NOT NULL,
    kind                 TEXT NOT NULL CHECK (kind IN
        ('INITIAL_FULL', 'CATCH_UP', 'INCREMENTAL', 'MANUAL')),
    status               TEXT NOT NULL CHECK (status IN
        ('RUNNING', 'WAITING_RETRY', 'UNKNOWN', 'PAUSED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    pause_reason         TEXT,
    started_at           TEXT NOT NULL,
    ended_at             TEXT,
    source_row_count     INTEGER NOT NULL DEFAULT 0,
    confirmed_row_count  INTEGER NOT NULL DEFAULT 0,
    created_at           TEXT NOT NULL
);
CREATE INDEX idx_run_task_id ON run (task_id);
CREATE INDEX idx_run_status ON run (status);
CREATE INDEX idx_run_started_at ON run (started_at);

-- 批次
CREATE TABLE batch (
    batch_id                 TEXT PRIMARY KEY,
    run_id                   TEXT NOT NULL REFERENCES run (run_id),
    batch_sequence           INTEGER NOT NULL,
    source_instance_id       TEXT NOT NULL,
    expected_sink_instance_id TEXT NOT NULL,
    payload_hash             TEXT NOT NULL,
    payload_size             INTEGER NOT NULL,
    spool_file_size          INTEGER,
    content_encoding         TEXT NOT NULL CHECK (content_encoding IN ('IDENTITY', 'GZIP')),
    row_count                INTEGER NOT NULL,
    start_cursor             TEXT,
    end_cursor               TEXT,
    status                   TEXT NOT NULL CHECK (status IN
        ('PENDING', 'PROCESSING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'SUPERSEDED')),
    attempt_count            INTEGER NOT NULL DEFAULT 0,
    first_send_started_at    TEXT,
    last_send_started_at     TEXT,
    spool_path               TEXT,
    created_at               TEXT NOT NULL,
    updated_at               TEXT NOT NULL
);
CREATE INDEX idx_batch_run_id ON batch (run_id);
CREATE INDEX idx_batch_status ON batch (status);
CREATE UNIQUE INDEX uq_batch_run_sequence ON batch (run_id, batch_sequence);

-- 检查点：每任务当前版本一条，只按 Sink 成功回执推进
CREATE TABLE checkpoint (
    task_id           TEXT PRIMARY KEY REFERENCES task (task_id),
    task_version      INTEGER NOT NULL,
    cursor_values     TEXT NOT NULL, -- JSON
    confirmed_batch_id TEXT NOT NULL REFERENCES batch (batch_id),
    confirmed_at      TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

-- 告警
CREATE TABLE alert (
    alert_id        TEXT PRIMARY KEY,
    event_type      TEXT NOT NULL,
    severity        TEXT NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'ERROR', 'CRITICAL')),
    task_id         TEXT REFERENCES task (task_id),
    run_id          TEXT REFERENCES run (run_id),
    batch_id        TEXT REFERENCES batch (batch_id),
    message         TEXT NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    occurred_at     TEXT NOT NULL,
    acknowledged_at TEXT
);
CREATE INDEX idx_alert_status ON alert (status);
CREATE INDEX idx_alert_task_id ON alert (task_id);
CREATE INDEX idx_alert_occurred_at ON alert (occurred_at);
