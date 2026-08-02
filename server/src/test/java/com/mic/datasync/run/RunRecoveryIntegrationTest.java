package com.mic.datasync.run;

import com.mic.datasync.shared.id.Identifiers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动恢复集成测试（内存 SQLite + 临时 dataDir）。
 */
@SpringBootTest
class RunRecoveryIntegrationTest {

    @Autowired
    private RunRecoveryService recoveryService;

    @Autowired
    private RunService runService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.mic.datasync.instance.RoleProperties roleProperties;

    @Test
    void nonTerminalRunBecomesPausedAfterRecovery() {
        Identifiers.TaskId taskId = Identifiers.TaskId.generate();
        Identifiers.RunId runId = Identifiers.RunId.generate();
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO run (run_id, task_id, task_name_snapshot, task_version, kind, status,
                    started_at, source_row_count, confirmed_row_count, created_at)
                VALUES (?, ?, 't', 1, 'INCREMENTAL', 'RUNNING', ?, 0, 0, ?)
                """, runId.toString(), taskId.toString(), now, now);

        recoveryService.scanAndRecover();

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM run WHERE run_id = ?", String.class, runId.toString());
        assertThat(status).isEqualTo("PAUSED");
        String pauseReason = jdbcTemplate.queryForObject(
                "SELECT pause_reason FROM run WHERE run_id = ?", String.class, runId.toString());
        assertThat(pauseReason).isEqualTo("启动恢复");
    }

    @Test
    void partFilesAreCleanedDuringRecovery() throws Exception {
        Path spoolRoot = Path.of(roleProperties.dataDir(), "spool");
        Path dir = spoolRoot.resolve("task-x").resolve("run-y");
        Files.createDirectories(dir);
        Path part = dir.resolve("1-00000000-0000-0000-0000-000000000000.payload.part");
        Files.writeString(part, "incomplete");

        recoveryService.scanAndRecover();

        assertThat(Files.exists(part)).isFalse();
    }
}
