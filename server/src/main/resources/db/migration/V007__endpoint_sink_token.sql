-- 每个 Sink 端可独立保存自己的 Sink 访问令牌（加密存储）
-- 发送批次时优先使用该端令牌；未配置时回退到 Source 端全局令牌
ALTER TABLE sync_endpoint ADD COLUMN sink_token_enc TEXT;
