package com.mic.datasync.webapi;

import com.mic.datasync.instance.InstanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实例信息接口（需管理员登录）。
 */
@RestController
@RequestMapping("/api/v1/instance")
public class InstanceController {

    private final InstanceService instanceService;

    public InstanceController(InstanceService instanceService) {
        this.instanceService = instanceService;
    }

    /** 返回实例身份、角色、版本与就绪状态。 */
    @GetMapping
    public InstanceInfo instance() {
        return new InstanceInfo(
                instanceService.instanceId().toString(),
                instanceService.startupId().toString(),
                instanceService.roles(),
                instanceService.applicationVersion(),
                "READY");
    }

    /** 实例信息响应。 */
    public record InstanceInfo(
            String instanceId,
            String startupId,
            String roles,
            String version,
            String readiness) {
    }
}
