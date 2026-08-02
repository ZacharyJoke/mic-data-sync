package com.mic.datasync.storage.spool;

import com.mic.datasync.instance.RoleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Spool 7 天清理：FAILED/CANCELLED 且结束超过 7 天的运行目录自动清理；
 * UNKNOWN 不自动清理（可能已提交，需人工核对）。路径安全：仅允许
 * {@code ${dataDir}/spool/{taskId}/{runId}} 系统生成路径，拒绝符号链接与越界。
 */
@Component
public class SpoolCleanupService {

    private static final Logger log = LoggerFactory.getLogger(SpoolCleanupService.class);
    private static final Duration RETENTION = Duration.ofDays(7);

    private final JdbcTemplate jdbcTemplate;
    private final RoleProperties roleProperties;

    public SpoolCleanupService(JdbcTemplate jdbcTemplate, RoleProperties roleProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.roleProperties = roleProperties;
    }

    /** 执行清理，返回清理的目录列表。 */
    public List<String> cleanup() {
        List<String> cleaned = new ArrayList<>();
        Path spoolRoot = Path.of(roleProperties.dataDir(), "spool");
        Instant cutoff = Instant.now().minus(RETENTION);
        // 终态失败/取消且超过保留期的运行
        List<String> runIds = jdbcTemplate.queryForList("""
                SELECT run_id FROM run
                WHERE status IN ('FAILED', 'CANCELLED') AND ended_at IS NOT NULL AND ended_at < ?
                """, String.class, cutoff.toString());
        for (String runId : runIds) {
            // 查找 run 的 spool 目录：spool/{taskId}/{runId}
            String taskId = jdbcTemplate.queryForObject(
                    "SELECT task_id FROM run WHERE run_id = ?", String.class, runId);
            if (taskId == null) {
                continue;
            }
            Path target = spoolRoot.resolve(taskId).resolve(runId);
            if (!isSafeWithinSpool(spoolRoot, target)) {
                log.warn("跳过越界/符号链接清理路径: {}", target);
                continue;
            }
            if (Files.isDirectory(target)) {
                try (var paths = Files.walk(target)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ex) {
                            log.warn("清理失败: {}", p);
                        }
                    });
                } catch (IOException ex) {
                    log.warn("清理目录失败: {}", target);
                }
                cleaned.add(target.toString());
            }
        }
        log.info("Spool 清理完成：清理 {} 个过期 FAILED/CANCELLED 运行目录", cleaned.size());
        return cleaned;
    }

    /** 校验路径位于 spoolRoot 内且非符号链接。 */
    private boolean isSafeWithinSpool(Path spoolRoot, Path target) {
        Path normalizedRoot = spoolRoot.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            return false;
        }
        // 检查路径中任何段不是符号链接
        Path current = normalizedRoot;
        for (Path segment : normalizedRoot.relativize(normalizedTarget)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return false;
            }
        }
        return true;
    }
}
