package com.mic.datasync.webapi;

import com.mic.datasync.dashboard.DashboardService;
import com.mic.datasync.dashboard.DashboardService.DashboardSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 工作台聚合接口（需管理员登录）。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboardService.summary(Instant.now());
    }
}
