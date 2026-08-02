package com.mic.datasync.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.shared.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 检查点仓储：按任务保存/读取分页游标。
 *
 * <p>Checkpoint 只根据 Sink 成功回执推进（单调前进），
 * 批次确认、检查点与运行统计在本地 SQLite 同一事务中更新。</p>
 */
@Component
public class CheckpointRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CheckpointRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 读取任务当前检查点。 */
    public Optional<Checkpoint> get(Identifiers.TaskId taskId) {
        List<Checkpoint> rows = jdbcTemplate.query("""
                SELECT task_id, task_version, cursor_values, confirmed_batch_id, confirmed_at
                FROM checkpoint WHERE task_id = ?
                """, (rs, rowNum) -> new Checkpoint(
                        Identifiers.TaskId.fromString(rs.getString("task_id")),
                        rs.getInt("task_version"),
                        fromJson(rs.getString("cursor_values")),
                        rs.getString("confirmed_batch_id"),
                        Instant.parse(rs.getString("confirmed_at"))),
                taskId.toString());
        return rows.stream().findFirst();
    }

    /** 保存检查点（UPSERT）。 */
    @Transactional
    public void upsert(Identifiers.TaskId taskId, int taskVersion,
                       Map<String, Object> cursorValues, Identifiers.BatchId confirmedBatchId) {
        String now = Instant.now().toString();
        String cursorJson = toJson(cursorValues);
        int updated = jdbcTemplate.update("""
                UPDATE checkpoint SET task_version = ?, cursor_values = ?,
                    confirmed_batch_id = ?, confirmed_at = ?, updated_at = ?
                WHERE task_id = ?
                """, taskVersion, cursorJson, confirmedBatchId.toString(), now, now, taskId.toString());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO checkpoint (task_id, task_version, cursor_values,
                        confirmed_batch_id, confirmed_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, taskId.toString(), taskVersion, cursorJson, confirmedBatchId.toString(), now, now);
        }
    }

    private String toJson(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("游标序列化失败", ex);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("游标反序列化失败", ex);
        }
    }

    /** 检查点。 */
    public record Checkpoint(
            Identifiers.TaskId taskId,
            int taskVersion,
            Map<String, Object> cursorValues,
            String confirmedBatchId,
            Instant confirmedAt) {
    }
}
