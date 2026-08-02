-- Sink 访问令牌（单行，加密存储 + 掩码展示）
CREATE TABLE sink_token (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    token_enc     TEXT NOT NULL, -- AES-GCM 密文
    token_display TEXT NOT NULL, -- 掩码展示（如 mic_****abcd）
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);
