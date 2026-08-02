package com.mic.datasync.sink;

import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.instance.InstanceService;
import com.mic.datasync.instance.RoleProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Sink 握手：返回实例身份、目标数据源列表（含各自就绪状态）、协议版本与最大批次限制。
 */
@Service
public class SinkHandshakeService {

    /** 当前传输协议版本。 */
    public static final int PROTOCOL_VERSION = 1;

    private final InstanceService instanceService;
    private final SinkReadinessService readinessService;
    private final DatabaseConfigService configService;
    private final RoleProperties roleProperties;

    public SinkHandshakeService(InstanceService instanceService,
                                SinkReadinessService readinessService,
                                DatabaseConfigService configService,
                                RoleProperties roleProperties) {
        this.instanceService = instanceService;
        this.readinessService = readinessService;
        this.configService = configService;
        this.roleProperties = roleProperties;
    }

    /** 执行握手。 */
    public HandshakeResponse handshake() {
        DatabaseConfig config = configService.getDefault(DatabaseRole.SINK).orElse(null);
        SinkReadinessService.ReadinessResult readiness = config == null
                ? readinessService.readiness()
                : readinessService.readinessFor(config);
        RoleProperties.Sink sink = roleProperties.sink();
        List<TargetDataSourceInfo> targetDataSources = configService.listSelf(DatabaseRole.SINK).stream()
                .map(target -> {
                    SinkReadinessService.ReadinessResult result = readinessService.readinessFor(target);
                    return new TargetDataSourceInfo(
                            target.id(),
                            target.name(),
                            target.databaseType().name(),
                            result.status(),
                            result.message(),
                            result.dbaSql());
                })
                .toList();
        return new HandshakeResponse(
                instanceService.instanceId().toString(),
                instanceService.startupId().toString(),
                config == null ? null : config.databaseType().name(),
                readiness.status(),
                readiness.message(),
                readiness.dbaSql(),
                PROTOCOL_VERSION,
                new BatchLimits(sink.maxRowsPerBatch(), sink.maxPayloadBytes()),
                sink.tlsInsecureSkipVerify(),
                targetDataSources);
    }

    /** 握手响应。 */
    public record HandshakeResponse(
            String sinkInstanceId,
            String startupId,
            String databaseType,
            String capabilityStatus,
            String message,
            String dbaSql,
            int protocolVersion,
            BatchLimits batchLimits,
            boolean tlsInsecureSkipVerify,
            List<TargetDataSourceInfo> targetDataSources) {
    }

    /** 最大批次限制。 */
    public record BatchLimits(int maxRowsPerBatch, int maxPayloadBytes) {
    }

    /** Sink 端目标数据源摘要（含就绪状态与 DBA 初始化 SQL）。 */
    public record TargetDataSourceInfo(
            String id,
            String name,
            String databaseType,
            String capabilityStatus,
            String message,
            String dbaSql) {
    }
}
