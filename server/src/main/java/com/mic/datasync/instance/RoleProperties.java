package com.mic.datasync.instance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 实例角色与运行参数配置。
 *
 * <p>由 application.yml 的 {@code mic.sync.*} 绑定，支持环境变量覆盖。</p>
 *
 * @param roles      部署角色（source/sink/source,sink）
 * @param dataDir    本地数据目录
 * @param source     Source 侧参数
 * @param sink       Sink 侧参数
 */
@ConfigurationProperties(prefix = "mic.sync")
public record RoleProperties(String roles, String dataDir, Source source, Sink sink) {

    public RoleProperties {
        roles = (roles == null || roles.isBlank()) ? "source,sink" : roles;
        dataDir = (dataDir == null || dataDir.isBlank()) ? "./data" : dataDir;
        source = source == null ? new Source(10, 1) : source;
        sink = sink == null ? new Sink(1000, 16 * 1024 * 1024, false) : sink;
    }

    /** Source 角色是否启用。 */
    public boolean isSourceEnabled() {
        return roles.contains("source");
    }

    /** Sink 角色是否启用。 */
    public boolean isSinkEnabled() {
        return roles.contains("sink");
    }

    /** Source 侧参数。 */
    public record Source(int maxTasks, int maxActiveRuns) {

        public Source {
            if (maxTasks <= 0) {
                maxTasks = 10;
            }
            if (maxActiveRuns <= 0) {
                maxActiveRuns = 1;
            }
        }
    }

    /** Sink 侧参数。 */
    public record Sink(int maxRowsPerBatch, int maxPayloadBytes, boolean tlsInsecureSkipVerify) {

        public Sink {
            if (maxRowsPerBatch <= 0) {
                maxRowsPerBatch = 1000;
            }
            if (maxPayloadBytes <= 0) {
                maxPayloadBytes = 16 * 1024 * 1024;
            }
        }
    }
}
