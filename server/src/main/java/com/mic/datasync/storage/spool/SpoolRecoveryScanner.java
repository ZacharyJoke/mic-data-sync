package com.mic.datasync.storage.spool;

import com.mic.datasync.shared.id.Identifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Spool 恢复扫描：清理未完成写入（.part）、孤儿文件（无 Batch 记录）、
 * 检测记录缺失与密文损坏，供启动恢复使用。
 */
@Component
public class SpoolRecoveryScanner {

    private static final Logger log = LoggerFactory.getLogger(SpoolRecoveryScanner.class);

    private final BatchSpoolStore spoolStore;
    private final JdbcTemplate jdbcTemplate;

    public SpoolRecoveryScanner(BatchSpoolStore spoolStore, JdbcTemplate jdbcTemplate) {
        this.spoolStore = spoolStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 扫描全部 Spool 目录并清理/报告。 */
    public RecoveryReport scan() {
        List<String> cleanedPartFiles = new ArrayList<>();
        List<String> orphanFiles = new ArrayList<>();
        List<String> corruptedFiles = new ArrayList<>();
        List<String> missingFiles = new ArrayList<>();

        Set<String> knownSpoolPaths = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT spool_path FROM batch WHERE spool_path IS NOT NULL", String.class));
        Set<String> seenFiles = new HashSet<>();

        Path root = spoolStore.spoolRoot();
        if (!Files.isDirectory(root)) {
            return new RecoveryReport(0, List.of(), List.of(), List.of(), List.of());
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                if (name.endsWith(".part")) {
                    try {
                        Files.deleteIfExists(file);
                        cleanedPartFiles.add(file.toString());
                    } catch (IOException ex) {
                        log.warn("清理 .part 失败: {}", file);
                    }
                    return;
                }
                if (!name.endsWith(".payload")) {
                    return;
                }
                seenFiles.add(file.toString());
                if (!knownSpoolPaths.contains(file.toString())) {
                    orphanFiles.add(file.toString());
                }
            });
        } catch (IOException ex) {
            log.warn("Spool 目录扫描失败: {}", ex.getMessage());
        }

        // 记录存在但文件缺失 / 密文损坏（解密失败）
        for (String path : knownSpoolPaths) {
            Path file = Path.of(path);
            if (!Files.exists(file)) {
                missingFiles.add(path);
                continue;
            }
            try {
                spoolStore.read(parseTaskId(file), parseRunId(file), parseSequence(file), parseBatchId(file));
            } catch (Exception ex) {
                corruptedFiles.add(path);
            }
        }
        return new RecoveryReport(cleanedPartFiles.size(), cleanedPartFiles, orphanFiles, corruptedFiles, missingFiles);
    }

    private Identifiers.TaskId parseTaskId(Path file) {
        return Identifiers.TaskId.fromString(file.getParent().getParent().getFileName().toString());
    }

    private Identifiers.RunId parseRunId(Path file) {
        return Identifiers.RunId.fromString(file.getParent().getFileName().toString());
    }

    private long parseSequence(Path file) {
        String name = file.getFileName().toString();
        return Long.parseLong(name.substring(0, name.indexOf('-')));
    }

    private Identifiers.BatchId parseBatchId(Path file) {
        String name = file.getFileName().toString();
        String batchId = name.substring(name.indexOf('-') + 1, name.length() - ".payload".length());
        return Identifiers.BatchId.fromString(batchId);
    }

    /** 恢复报告。 */
    public record RecoveryReport(
            int cleanedPartCount,
            List<String> cleanedPartFiles,
            List<String> orphanFiles,
            List<String> corruptedFiles,
            List<String> missingFiles) {
    }
}
