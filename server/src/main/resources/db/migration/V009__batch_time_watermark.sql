-- 批次时间水位：该批次最后一行 updatedTimeField 的值（ISO-8601 字符串），
-- 供运行详情页按批次展示时间水位。历史批次该列为 NULL，页面显示 '-'
ALTER TABLE batch ADD COLUMN time_watermark TEXT;
