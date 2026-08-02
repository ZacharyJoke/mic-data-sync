package com.mic.datasync.run;

import com.mic.datasync.instance.RoleProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 全局 Run 名额守卫：同时最多一个活动 Run（默认），无名额不排队。
 */
@Component
public class RunSlotGuard {

    private static final String ACTIVE_STATUSES = "'RUNNING', 'WAITING_RETRY', 'UNKNOWN', 'PAUSED'";

    private final JdbcTemplate jdbcTemplate;
    private final RoleProperties roleProperties;

    public RunSlotGuard(JdbcTemplate jdbcTemplate, RoleProperties roleProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.roleProperties = roleProperties;
    }

    /** 当前活动 Run 数量。 */
    public int activeRunCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM run WHERE status IN (" + ACTIVE_STATUSES + ")",
                Integer.class);
        return count == null ? 0 : count;
    }

    /** 是否有可用名额。 */
    public boolean hasSlot() {
        return activeRunCount() < roleProperties.source().maxActiveRuns();
    }
}
