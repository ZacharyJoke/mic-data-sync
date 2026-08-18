package com.mic.datasync.storage.spool;

import com.mic.datasync.instance.RoleProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Spool 7 天清理与路径安全测试。
 */
class SpoolCleanupServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SpoolCleanupService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        RoleProperties properties = new RoleProperties("source,sink", tempDir.toString(),
                tempDir.resolve("drivers").toString(),
                new RoleProperties.Source(10, 1),
                new RoleProperties.Sink(1000, 16 * 1024 * 1024, false));
        service = new SpoolCleanupService(jdbcTemplate, properties);
    }

    @Test
    void expiredFailedRunDirectoryIsCleaned() throws Exception {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of("run-1"));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString()))
                .thenReturn("task-1");
        Path dir = tempDir.resolve("spool").resolve("task-1").resolve("run-1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1-batch.payload"), "x");

        List<String> cleaned = service.cleanup();

        assertThat(cleaned).contains(dir.toString());
        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void recentFailedRunIsNotCleaned() throws Exception {
        // SQL 层按 ended_at < cutoff 过滤：无过期结果时不清理任何目录
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of());
        Path dir = tempDir.resolve("spool").resolve("task-recent").resolve("run-recent");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1-batch.payload"), "x");

        service.cleanup();

        assertThat(Files.exists(dir)).isTrue();
    }

    @Test
    void noRunsToCleanLeavesDirectoriesIntact() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of());

        List<String> cleaned = service.cleanup();

        assertThat(cleaned).isEmpty();
    }

    @Test
    void symlinkedPathIsRejected() throws Exception {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString()))
                .thenReturn(List.of("run-link"));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), anyString()))
                .thenReturn("task-link");
        // 创建符号链接目录（指向 spool 外）
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path spool = tempDir.resolve("spool");
        Files.createDirectories(spool.resolve("task-link"));
        Path link = spool.resolve("task-link").resolve("run-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException ex) {
            return; // 平台不支持符号链接则跳过
        }

        List<String> cleaned = service.cleanup();

        assertThat(cleaned).isEmpty();
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }
}
