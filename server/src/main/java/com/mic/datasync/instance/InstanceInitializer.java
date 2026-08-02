package com.mic.datasync.instance;

import com.mic.datasync.auth.AdminAuthService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动初始化：确保实例身份持久化、管理员账号初始化。
 * 任一步失败都会阻止应用启动，避免运行在未初始化的状态上。
 */
@Component
public class InstanceInitializer implements ApplicationRunner {

    private final InstanceService instanceService;
    private final AdminAuthService adminAuthService;

    public InstanceInitializer(InstanceService instanceService, AdminAuthService adminAuthService) {
        this.instanceService = instanceService;
        this.adminAuthService = adminAuthService;
    }

    @Override
    public void run(ApplicationArguments args) {
        instanceService.ensureInitialized();
        adminAuthService.initializeAdminIfNeeded();
    }
}
