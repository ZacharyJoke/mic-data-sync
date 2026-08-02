package com.mic.datasync.bootstrap;

import com.mic.datasync.run.RunRecoveryService;
import com.mic.datasync.storage.spool.SpoolCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动恢复：先扫描非终态 Run/Spool 并执行 7 天清理，再开放 Source 执行能力。
 * 在实例身份/管理员初始化之后执行。
 */
@Component
@Order(20)
public class StartupRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecoveryRunner.class);

    private final RunRecoveryService recoveryService;
    private final SpoolCleanupService cleanupService;

    public StartupRecoveryRunner(RunRecoveryService recoveryService, SpoolCleanupService cleanupService) {
        this.recoveryService = recoveryService;
        this.cleanupService = cleanupService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            RunRecoveryService.RecoveryResult recovery = recoveryService.scanAndRecover();
            List<String> cleaned = cleanupService.cleanup();
            log.info("启动恢复完成：暂停非终态 Run {} 个，清理过期目录 {} 个",
                    recovery.pausedRunCount(), cleaned.size());
        } catch (Exception ex) {
            // 恢复失败不阻止启动，但记录错误供运维排查
            log.error("启动恢复执行异常", ex);
        }
    }
}
