-- 患者数据生成器：INSERT INTO ... SELECT generate_series
-- 用法：psql -v rows=100000 -f patient-data-generator.sql
\set rows 100000

INSERT INTO mic_sync.patient (id, name, status, del_flag, note, updated_time)
SELECT g,
       'patient_' || g,
       CASE WHEN g % 100 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END,
       CASE WHEN g % 1000 = 0 THEN 1 ELSE 0 END,
       repeat('note-' || g, 20),
       now() - ((g % 1000) || ' minutes')::interval
FROM generate_series(1, :rows) AS g
ON CONFLICT (id) DO NOTHING;
