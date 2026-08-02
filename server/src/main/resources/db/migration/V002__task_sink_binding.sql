-- 任务与 Sink 绑定：期望的 Sink 实例 ID（有状态请求必须匹配）
ALTER TABLE task ADD COLUMN expected_sink_instance_id TEXT;
