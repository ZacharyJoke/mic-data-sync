package com.mic.datasync.run;

import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.storage.spool.SpoolRecoveryScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 启动恢复：先扫描非终态 Run 与 Spool，再开放 Source 执行能力。
 *
 * <p>清理未完成写入/孤儿 Spool 文件；非终态 Run 置为 PAUSED（等待用户继续，
 * 避免进程重启后自动重复执行）；UNKNOWN 批次保持，由继续流程核对回执。</p>
 */
@Service
public class RunRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RunRecoveryService.class);

    private final RunService runService;
    private final SpoolRecoveryScanner spoolScanner;

    public RunRecoveryService(RunService runService, SpoolRecoveryScanner spoolScanner) {
        this.runService = runService;
        this.spoolScanner = spoolScanner;
    }

    /** 启动恢复扫描。 */
    public RecoveryResult scanAndRecover() {
        SpoolRecoveryScanner.RecoveryReport spoolReport = spoolScanner.scan();
        List<RunRecord> nonTerminal = runService.list().stream()
                .filter(r -> !r.status().isTerminal())
                .toList();
        // 非终态 Run 置 PAUSED，等待用户继续（不在启动时自动重复执行）
        for (RunRecord run : nonTerminal) {
            if (run.status() != RunStatus.PAUSED) {
                runService.updateStatus(run.runId(), RunStatus.PAUSED, "启动恢复",
                        run.sourceRowCount(), run.confirmedRowCount());
                log.info("启动恢复：runId={} 已置 PAUSED，等待继续", run.runId());
            }
        }
        log.info("启动恢复完成：清理 .part {} 个、孤儿 {} 个、损坏 {} 个、缺失 {} 个，非终态 Run {} 个",
                spoolReport.cleanedPartCount(), spoolReport.orphanFiles().size(),
                spoolReport.corruptedFiles().size(), spoolReport.missingFiles().size(), nonTerminal.size());
        return new RecoveryResult(
                spoolReport.cleanedPartCount(),
                spoolReport.orphanFiles().size(),
                spoolReport.corruptedFiles().size(),
                spoolReport.missingFiles().size(),
                nonTerminal.size());
    }

    /** 恢复结果。 */
    public record RecoveryResult(
            int cleanedPartCount,
            int orphanFileCount,
            int corruptedFileCount,
            int missingFileCount,
            int pausedRunCount) {
    }
}
