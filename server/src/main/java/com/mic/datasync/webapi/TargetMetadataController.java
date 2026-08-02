package com.mic.datasync.webapi;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
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
 * 目标表元数据接口（需管理员登录，依赖 Sink 数据库配置），
 * 用于任务创建向导的字段映射步骤。
 */
@RestController
@RequestMapping("/api/v1/target/metadata")
public class TargetMetadataController {

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final DatabaseAdapterFactory adapterFactory;

    public TargetMetadataController(DatabaseConfigService configService,
                                    ConnectionFactory connectionFactory,
                                    DatabaseAdapterFactory adapterFactory) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.adapterFactory = adapterFactory;
    }

    /** 读取目标表元数据（字段/主键/唯一索引）。 */
    @GetMapping("/{schema}/{table}")
    public ResponseEntity<?> tableMetadata(@PathVariable String schema, @PathVariable String table,
                                           @RequestParam(required = false) String dataSourceId) {
        DatabaseConfig config = resolveConfig(dataSourceId);
        if (config == null) {
            return ResponseEntity.badRequest().body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), "未配置或找不到 Sink 目标数据源",
                    UUID.randomUUID().toString(), Map.of()));
        }
        try (Connection connection = connectionFactory.open(config)) {
            TargetDatabaseAdapter adapter = adapterFactory.targetAdapter(config.databaseType());
            TableMetadata metadata = adapter.readTableMetadata(connection, schema, table);
            return ResponseEntity.ok(new TargetMetadataResponse(
                    metadata.schema(), metadata.table(),
                    metadata.columns().stream().map(this::toColumn).toList(),
                    metadata.primaryKeyColumns(),
                    metadata.uniqueIndexes()));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                    ErrorCode.DATABASE_CONNECTION_FAILED.name(), "目标表元数据读取失败",
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
}
