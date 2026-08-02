-- kingbase Target 夹具：患者镜像表（同步目标）
CREATE SCHEMA IF NOT EXISTS mic_sync;
CREATE TABLE IF NOT EXISTS mic_sync.patient (
    id           BIGINT PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    del_flag     SMALLINT     NOT NULL DEFAULT 0,
    note         TEXT,
    updated_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
