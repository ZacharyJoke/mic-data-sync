package com.mic.datasync.webapi;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.SourceDatabaseAdapter;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Source 元数据接口（需管理员登录，依赖 Source 数据库配置）。
 */
@RestController
@RequestMapping("/api/v1/source/metadata")
public class SourceMetadataController {

    private static final int MAX_SAMPLE_ROWS = 20;

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final DatabaseAdapterFactory adapterFactory;

    public SourceMetadataController(DatabaseConfigService configService,
                                    ConnectionFactory connectionFactory,
                                    DatabaseAdapterFactory adapterFactory) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.adapterFactory = adapterFactory;
    }

    /** 列出 Source 数据库可用 Schema。 */
    @GetMapping("/schemas")
    public ResponseEntity<?> listSchemas(@RequestParam(required = false) String dataSourceId) {
        return withSourceConnection(dataSourceId, (connection, adapter) -> {
            List<String> schemas = adapter.listSchemas(connection);
            return ResponseEntity.ok(Map.of("schemas", schemas));
        });
    }

    /** 列出指定 Schema 下的表。 */
    @GetMapping("/schemas/{schema}/tables")
    public ResponseEntity<?> listTables(@PathVariable String schema,
                                        @RequestParam(required = false) String dataSourceId) {
        return withSourceConnection(dataSourceId, (connection, adapter) -> {
            List<String> tables = adapter.listTables(connection, schema);
            return ResponseEntity.ok(Map.of("tables", tables));
        });
    }

    /** 读取表元数据并给出分页 Key 建议（主键优先，其次全部非空的唯一索引）。 */
    @GetMapping("/schemas/{schema}/tables/{table}")
    public ResponseEntity<?> tableMetadata(@PathVariable String schema, @PathVariable String table,
                                           @RequestParam(required = false) String dataSourceId) {
        return withSourceConnection(dataSourceId, (connection, adapter) -> {
            TableMetadata metadata = adapter.readTableMetadata(connection, schema, table);
            List<List<String>> suggestions = paginationKeySuggestions(metadata);
            return ResponseEntity.ok(new TableMetadataResponse(
                    metadata.schema(), metadata.table(),
                    metadata.columns().stream().map(this::toColumn).toList(),
                    metadata.primaryKeyColumns(),
                    metadata.uniqueIndexes(),
                    suggestions));
        });
    }

    /** 测试查询：最多 20 行（目标表映射前预览数据）。 */
    @PostMapping("/schemas/{schema}/tables/{table}/sample")
    public ResponseEntity<?> sampleRows(@PathVariable String schema, @PathVariable String table,
                                        @RequestBody SampleRequest request,
                                        @RequestParam(required = false) String dataSourceId) {
        return withSourceConnection(dataSourceId, (connection, adapter) -> {
            List<String> columns = request.columns() == null || request.columns().isEmpty()
                    ? adapter.readTableMetadata(connection, schema, table).columns().stream()
                            .map(ColumnMetadata::name).toList()
                    : request.columns();
            List<List<Object>> rows = adapter.sampleRows(connection, schema, table, columns, MAX_SAMPLE_ROWS);
            return ResponseEntity.ok(new SampleResponse(columns, rows));
        });
    }

    private List<List<String>> paginationKeySuggestions(TableMetadata metadata) {
        List<List<String>> suggestions = new ArrayList<>();
        // 主键优先
        if (metadata.hasPrimaryKey()) {
            suggestions.add(metadata.primaryKeyColumns());
        }
        // 全部非空的唯一索引
        for (List<String> uniqueIndex : metadata.uniqueIndexes()) {
            if (allNotNullable(metadata, uniqueIndex)) {
                suggestions.add(uniqueIndex);
            }
        }
        return suggestions;
    }

    private boolean allNotNullable(TableMetadata metadata, List<String> columns) {
        Map<String, ColumnMetadata> byName = metadata.columnMap();
        for (String column : columns) {
            ColumnMetadata columnMetadata = byName.get(column.toLowerCase());
            if (columnMetadata == null || columnMetadata.nullable()) {
                return false;
            }
        }
        return true;
    }

    private TableMetadataResponse.Column toColumn(ColumnMetadata column) {
        return new TableMetadataResponse.Column(
                column.name(), column.typeName(), column.size(), column.nullable(), column.primaryKey());
    }

    /** 在 Source 数据库连接上执行读取操作；无 Source 配置时返回 400。 */
    private <T> ResponseEntity<T> withSourceConnection(
            String dataSourceId,
            SqlAction<T> action) {
        DatabaseConfig config = resolveConfig(dataSourceId);
        if (config == null) {
            return ResponseEntity.badRequest().body((T) validationError("未配置 Source 数据库，请先在数据库管理中配置"));
        }
        try (Connection connection = connectionFactory.open(config)) {
            SourceDatabaseAdapter adapter = adapterFactory.sourceAdapter(config.databaseType());
            return action.execute(connection, adapter);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body((T) new ApiError(ErrorCode.DATABASE_CONNECTION_FAILED.name(),
                            "Source 元数据读取失败: " + safeMessage(ex),
                            UUID.randomUUID().toString(), Map.of()));
        }
    }

    private DatabaseConfig resolveConfig(String dataSourceId) {
        if (dataSourceId != null && !dataSourceId.isBlank()) {
            return configService.get(dataSourceId)
                    .filter(config -> config.role() == DatabaseRole.SOURCE)
                    .orElse(null);
        }
        return configService.getDefault(DatabaseRole.SOURCE).orElse(null);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    private ApiError validationError(String message) {
        return new ApiError(ErrorCode.VALIDATION_FAILED.name(), message, UUID.randomUUID().toString(), Map.of());
    }

    @FunctionalInterface
    private interface SqlAction<T> {
        ResponseEntity<T> execute(Connection connection, SourceDatabaseAdapter adapter) throws Exception;
    }

    /** 表元数据响应。 */
    public record TableMetadataResponse(
            String schema,
            String table,
            List<Column> columns,
            List<String> primaryKeyColumns,
            List<List<String>> uniqueIndexes,
            List<List<String>> paginationKeySuggestions) {

        public record Column(String name, String typeName, int size, boolean nullable, boolean primaryKey) {
        }
    }

    /** 测试查询请求。 */
    public record SampleRequest(List<String> columns) {
    }

    /** 测试查询响应。 */
    public record SampleResponse(List<String> columns, List<List<Object>> rows) {
    }
}
