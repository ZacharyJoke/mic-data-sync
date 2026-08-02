package com.mic.datasync.webapi;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统级接口。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    /**
     * 健康探测接口，用于验证服务是否存活。
     */
    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of(
                "status", "UP",
                "application", "mic-data-sync"
        );
    }
}
