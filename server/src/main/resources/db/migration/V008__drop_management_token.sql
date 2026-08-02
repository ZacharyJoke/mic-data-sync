-- 统一使用 Sink 访问令牌：移除管理令牌（Agent 管理接口改用 Sink 令牌认证）
DROP TABLE management_token;
ALTER TABLE sync_endpoint DROP COLUMN management_token_enc;
