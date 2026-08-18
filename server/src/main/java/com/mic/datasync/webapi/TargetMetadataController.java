package com.mic.datasync.webapi;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.endpoint.AgentClient;
import com.mic.datasync.endpoint.AgentProtocol;
import com.mic.datasync.endpoint.EndpointRecord;
import com.mic.datasync.endpoint.EndpointService;
import com.mic.datasync.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 目标表元数据接口（需管理员登录）。
 *
 * <p>本地 self-sink 直接在本实例执行；远程 Sink 通过 Agent API 转发到
 * 所属端执行，避免控制台（Source 端）直连 Sink 数据源。</p>
 */
@RestController
@RequestMapping("/api/v1/target/metadata")
public class TargetMetadataController {

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final DatabaseAdapterFactory adapterFactory;
    private final EndpointService endpointService;
    private final AgentClient agentClient;

    public TargetMetadataController(DatabaseConfigService configService,
                                    ConnectionFactory connectionFactory,
                                    DatabaseAdapterFactory adapterFactory,
                                    EndpointService endpointService,
                                    AgentClient agentClient) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.adapterFactory = adapterFactory;
        this.endpointService = endpointService;
        this.agentClient = agentClient;
    }

    /** 列出 Sink 目标库可用 Schema。 */
    @GetMapping("/schemas")
    public ResponseEntity<?> listSchemas(@RequestParam(required = false) String dataSourceId) {
        return onTarget(dataSourceId,
                (connection, adapter) ->
                        ResponseEntity.ok(Map.of("schemas", adapter.listSchemas(connection))),
                (endpoint, config) -> ResponseEntity.ok(Map.of("schemas",
                        agentClient.listTargetSchemas(endpoint, config.id()))));
    }

    /** 列出指定 Schema 下的目标表。 */
    @GetMapping("/schemas/{schema}/tables")
    public ResponseEntity<?> listTables(@PathVariable String schema,
                                        @RequestParam(required = false) String dataSourceId) {
        return onTarget(dataSourceId,
                (connection, adapter) ->
                        ResponseEntity.ok(Map.of("tables", adapter.listTables(connection, schema))),
                (endpoint, config) -> ResponseEntity.ok(Map.of("tables",
                        agentClient.listTargetTables(endpoint, schema, config.id()))));
    }

    /** 读取目标表元数据（字段/主键/唯一索引）。 */
    @GetMapping("/{schema}/{table}")
    public ResponseEntity<?> tableMetadata(@PathVariable String schema, @PathVariable String table,
                                           @RequestParam(required = false) String dataSourceId) {
        return onTarget(dataSourceId,
                (connection, adapter) -> {
                    TableMetadata metadata = adapter.readTableMetadata(connection, schema, table);
                    return ResponseEntity.ok(new TargetMetadataResponse(
                            metadata.schema(), metadata.table(),
                            metadata.columns().stream().map(this::toColumn).toList(),
                            metadata.primaryKeyColumns(),
                            metadata.uniqueIndexes()));
                },
                (endpoint, config) -> {
                    AgentProtocol.TargetTableMetadata metadata = agentClient.readTargetTableMetadata(
                            endpoint, schema, table, config.id());
                    return ResponseEntity.ok(new TargetMetadataResponse(
                            metadata.schema(), metadata.table(),
                            metadata.columns().stream().map(column -> new Column(
                                    column.name(), column.typeName(), column.nullable(), column.primaryKey()))
                                    .toList(),
                            metadata.primaryKeyColumns(),
                            metadata.uniqueIndexes()));
                });
    }

    private ResponseEntity<?> onTarget(
            String dataSourceId,
            LocalTargetAction localAction,
            RemoteTargetAction remoteAction) {
        DatabaseConfig config = resolveConfig(dataSourceId);
        if (config == null) {
            return ResponseEntity.badRequest().body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), "未配置或找不到 Sink 目标数据源",
                    UUID.randomUUID().toString(), Map.of()));
        }
        EndpointRecord endpoint = endpointService.get(config.endpointId()).orElse(null);
        if (endpoint == null) {
            return ResponseEntity.badRequest().body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), "目标数据源所属端不存在",
                    UUID.randomUUID().toString(), Map.of()));
        }
        try {
            if (endpoint.isSelf()) {
                try (Connection connection = connectionFactory.open(config)) {
                    TargetDatabaseAdapter adapter = adapterFactory.targetAdapter(config.databaseType());
                    return localAction.execute(connection, adapter);
                }
            }
            return remoteAction.execute(endpoint, config);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError(
                    ErrorCode.DATABASE_CONNECTION_FAILED.name(),
                    "目标元数据读取失败: " + safeMessage(ex),
                    UUID.randomUUID().toString(), Map.of()));
        }
    }

    private DatabaseConfig resolveConfig(String dataSourceId) {
        if (dataSourceId != null && !dataSourceId.isBlank()) {
            return configService.get(dataSourceId)
                    .filter(config -> config.role() == DatabaseRole.SINK)
                    .orElse(null);
        }
        return configService.getDefault(DatabaseRole.SINK).orElse(null);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    private Column toColumn(ColumnMetadata column) {
        return new Column(column.name(), column.typeName(), column.nullable(), column.primaryKey());
    }

    /** 目标表元数据响应。 */
    public record TargetMetadataResponse(
            String schema,
            String table,
            List<Column> columns,
            List<String> primaryKeyColumns,
            List<List<String>> uniqueIndexes) {
    }

    public record Column(String name, String typeName, boolean nullable, boolean primaryKey) {
    }

    @FunctionalInterface
    private interface LocalTargetAction {
        ResponseEntity<?> execute(Connection connection, TargetDatabaseAdapter adapter) throws Exception;
    }

    @FunctionalInterface
    private interface RemoteTargetAction {
        ResponseEntity<?> execute(EndpointRecord endpoint, DatabaseConfig config) throws Exception;
    }
}
