ALTER TABLE run ADD COLUMN previous_run_id TEXT REFERENCES run (run_id);
CREATE INDEX idx_run_previous_run_id ON run (previous_run_id);

CREATE TABLE run_failure (
    run_id          TEXT PRIMARY KEY REFERENCES run (run_id),
    stage           TEXT NOT NULL,
    error_code      TEXT NOT NULL,
    summary         TEXT NOT NULL,
    impact          TEXT NOT NULL,
    request_id      TEXT NOT NULL,
    retryable       INTEGER NOT NULL CHECK (retryable IN (0, 1)),
    occurred_at     TEXT NOT NULL
);

CREATE TABLE run_retry_request (
    original_run_id TEXT NOT NULL REFERENCES run (run_id),
    actor           TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    new_run_id      TEXT NOT NULL REFERENCES run (run_id),
    created_at      TEXT NOT NULL,
    PRIMARY KEY (original_run_id, actor, idempotency_key)
);
