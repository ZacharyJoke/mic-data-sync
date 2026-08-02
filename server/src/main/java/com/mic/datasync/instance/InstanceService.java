package com.mic.datasync.instance;

import com.mic.datasync.shared.id.Identifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 实例身份管理。
 *
 * <ul>
 *   <li>{@code instanceId}：首次初始化 dataDir 时生成 UUID v4 并持久化，生命周期内保持不变；</li>
 *   <li>{@code startupId}：每次进程启动重新生成，仅用于日志/诊断，不参与任务绑定与认证。</li>
 * </ul>
 */
@Service
public class InstanceService {

    private static final Logger log = LoggerFactory.getLogger(InstanceService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RoleProperties roleProperties;
    private final String applicationVersion;
    private final Identifiers.InstanceId startupId;

    private volatile Identifiers.InstanceId instanceId;

    public InstanceService(
            JdbcTemplate jdbcTemplate,
            RoleProperties roleProperties,
            @Value("${mic.sync.version:0.1.0-SNAPSHOT}") String applicationVersion) {
        this.jdbcTemplate = jdbcTemplate;
        this.roleProperties = roleProperties;
        this.applicationVersion = applicationVersion;
        this.startupId = Identifiers.InstanceId.generate();
    }

    /**
     * 确保实例身份已初始化并返回持久化的 instanceId（幂等）。
     * 重复调用始终返回同一值；新库首次调用会生成并写入。
     */
    public synchronized Identifiers.InstanceId ensureInitialized() {
        if (instanceId != null) {
            return instanceId;
        }
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT instance_id FROM client_instance ORDER BY rowid LIMIT 1", String.class);
        if (existing.isEmpty()) {
            Identifiers.InstanceId generated = Identifiers.InstanceId.generate();
            String now = Instant.now().toString();
            jdbcTemplate.update("""
                    INSERT INTO client_instance
                        (instance_id, application_version, protocol_version, roles,
                         data_dir, source_max_tasks, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    generated.toString(),
                    applicationVersion,
                    1,
                    roleProperties.roles(),
                    roleProperties.dataDir(),
                    roleProperties.source() == null ? 10 : roleProperties.source().maxTasks(),
                    now);
            instanceId = generated;
            log.info("已初始化实例身份 instanceId={}", instanceId);
        } else {
            instanceId = Identifiers.InstanceId.fromString(existing.get(0));
            log.info("已恢复实例身份 instanceId={}", instanceId);
        }
        return instanceId;
    }

    /** 当前持久化的实例 ID（必要时先初始化）。 */
    public Identifiers.InstanceId instanceId() {
        return ensureInitialized();
    }

    /** 本次进程启动生成的诊断用 ID，不持久化。 */
    public Identifiers.InstanceId startupId() {
        return startupId;
    }

    /** 部署角色配置原文（source/sink/source,sink）。 */
    public String roles() {
        return roleProperties.roles();
    }

    public boolean isSourceEnabled() {
        return roleProperties.isSourceEnabled();
    }

    public boolean isSinkEnabled() {
        return roleProperties.isSinkEnabled();
    }

    /** 应用版本。 */
    public String applicationVersion() {
        return applicationVersion;
    }
}
