package com.mic.datasync.webapi;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.endpoint.AgentProtocol;
import com.mic.datasync.instance.InstanceService;
import com.mic.datasync.sink.SinkHandshakeService;
import com.mic.datasync.sink.SinkTokenService;
import com.mic.datasync.shared.error.ErrorCode;
import com.mic.datasync.task.FieldMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.sql.Connection;

/**
 * Agent 管理接口（Authorization: Bearer Sink 访问令牌认证）。
 *
 * <p>供控制台远程纳管本实例：数据源目录与增删改查、连接测试、目标预检、令牌状态。
 * 所有操作只作用于本实例自身的端（self-source / self-sink）。</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final DatabaseAdapterFactory adapterFactory;
    private final InstanceService instanceService;
    private final SinkHandshakeService handshakeService;
    private final SinkTokenService sinkTokenService;

    public AgentController(DatabaseConfigService configService,
                           ConnectionFactory connectionFactory,
                           DatabaseAdapterFactory adapterFactory,
                           InstanceService instanceService,
                           SinkHandshakeService handshakeService,
                           SinkTokenService sinkTokenService) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.adapterFactory = adapterFactory;
        this.instanceService = instanceService;
        this.handshakeService = handshakeService;
        this.sinkTokenService = sinkTokenService;
    }

    /** 探活：返回实例身份、角色、Sink 握手与数据源目录。 */
    @PostMapping("/probe")
    public AgentProtocol.AgentProbeResponse probe() {
        List<String> roles = Arrays.stream(instanceService.roles().split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(String::toUpperCase)
                .toList();
        SinkHandshakeService.HandshakeResponse sinkStatus =
                instanceService.isSinkEnabled() ? handshakeService.handshake() : null;
        List<AgentProtocol.DataSourceInfo> dataSources = new ArrayList<>();
        configService.listSelf(DatabaseRole.SOURCE).forEach(config ->
                dataSources.add(toDataSourceInfo(config)));
        configService.listSelf(DatabaseRole.SINK).forEach(config ->
                dataSources.add(toDataSourceInfo(config)));
        return new AgentProtocol.AgentProbeResponse(
                instanceService.instanceId().toString(),
                roles,
                sinkStatus,
                dataSources);
    }

    /** 本实例指定角色下的数据源目录。 */
    @GetMapping("/data-sources")
    public ResponseEntity<?> list(@RequestParam String role) {
        Optional<DatabaseRole> parsed = parseRole(role);
        if (parsed.isEmpty()) {
            return badRequest("角色只能是 SOURCE 或 SINK");
        }
        return ResponseEntity.ok(configService.listSelf(parsed.get()).stream()
                .map(this::toResponse)
                .toList());
    }

    /** 在本实例自身端下创建数据源档案。 */
    @PostMapping("/data-sources")
    public ResponseEntity<?> create(@RequestBody DataSourceRequest request) {
        Optional<DatabaseRole> parsed = parseRole(request.role());
        if (parsed.isEmpty()) {
            return badRequest("角色只能是 SOURCE 或 SINK");
        }
        DatabaseType type;
        try {
            type = DatabaseType.valueOf(request.product());
        } catch (IllegalArgumentException ex) {
            return badRequest("数据库类型只能是 KINGBASE_ES 或 OPEN_GAUSS");
        }
        try {
            DatabaseConfig saved = configService.create(
                    selfEndpointId(parsed.get()), request.id(), request.name(), type,
                    request.jdbcUrl(), request.username(), request.password(), request.driverType());
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    /** 更新本实例的数据源档案（密码/名称为空保留原值）。 */
    @PutMapping("/data-sources/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody DataSourceRequest request) {
        DatabaseType type;
        try {
            type = DatabaseType.valueOf(request.product());
        } catch (IllegalArgumentException ex) {
            return badRequest("数据库类型只能是 KINGBASE_ES 或 OPEN_GAUSS");
        }
        try {
            return ResponseEntity.ok(toResponse(configService.update(
                    id, request.name(), type, request.jdbcUrl(), request.username(),
                    request.password(), request.driverType())));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    /** 删除本实例的数据源档案；被任务引用时返回 409。 */
    @DeleteMapping("/data-sources/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            if (configService.delete(id)) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), "数据源不存在",
                    UUID.randomUUID().toString(), Map.of()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), ex.getMessage(),
                    UUID.randomUUID().toString(), Map.of()));
        }
    }

    /** 连接测试（不保存；密码为空时回退到本实例已保存密码）。 */
    @PostMapping("/data-sources/test")
    public ResponseEntity<?> test(@RequestBody DataSourceRequest request) {
        Optional<DatabaseRole> parsed = parseRole(request.role());
        if (parsed.isEmpty()) {
            return badRequest("角色只能是 SOURCE 或 SINK");
        }
        DatabaseType type;
        try {
            type = DatabaseType.valueOf(request.product());
        } catch (IllegalArgumentException ex) {
            return badRequest("数据库类型只能是 KINGBASE_ES 或 OPEN_GAUSS");
        }
        String password = request.password() == null ? "" : request.password();
        if (password.isBlank() && request.id() != null && !request.id().isBlank()) {
            Optional<DatabaseConfig> existing = configService.get(request.id());
            if (existing.isPresent()) {
                password = existing.get().password();
            }
        }
        DatabaseConfig probe = new DatabaseConfig(
                request.id(), selfEndpointId(parsed.get()), request.name(), parsed.get(), type,
                request.jdbcUrl(), request.username(), password, request.driverType(), null, null);
        return ResponseEntity.ok(connectionFactory.testConnection(probe));
    }

    /** 目标表预检：校验目标表结构、映射、唯一约束与回执表（控制台创建任务前下发）。 */
    @PostMapping("/target/preflight")
    public ResponseEntity<?> targetPreflight(@RequestBody AgentProtocol.TargetPreflightRequest request) {
        DatabaseConfig target = configService.get(request.targetDataSourceId()).orElse(null);
        if (target == null) {
            return badRequest("目标数据源不存在: " + request.targetDataSourceId());
        }
        List<AgentProtocol.PreflightIssue> issues = new ArrayList<>();
        try (Connection connection = connectionFactory.open(target)) {
            TargetDatabaseAdapter adapter = adapterFactory.targetAdapter(target.databaseType());
            TableMetadata metadata = adapter.readTableMetadata(connection, request.schema(), request.table());
            Set<String> targetSet = new HashSet<>();
            metadata.columns().forEach(column -> targetSet.add(column.name().toLowerCase(Locale.ROOT)));
            Set<String> sourceSet = new HashSet<>();
            (request.sourceColumns() == null ? List.<String>of() : request.sourceColumns())
                    .forEach(column -> sourceSet.add(column.toLowerCase(Locale.ROOT)));
            Set<String> mappedTargets = new HashSet<>();
            for (FieldMapping mapping : request.fieldMappings() == null
                    ? List.<FieldMapping>of() : request.fieldMappings()) {
                if (!sourceSet.isEmpty() && !sourceSet.contains(mapping.sourceField().toLowerCase(Locale.ROOT))) {
                    issues.add(issue("VALIDATION_FAILED", "源字段不存在: " + mapping.sourceField(),
                            "fieldMappings." + mapping.sourceField(), "SOURCE_VALIDATION",
                            "重新加载源表字段并修正映射"));
                }
                if (!targetSet.contains(mapping.targetField().toLowerCase(Locale.ROOT))) {
                    issues.add(issue("VALIDATION_FAILED", "目标字段不存在: " + mapping.targetField(),
                            "fieldMappings." + mapping.sourceField(), "TARGET_VALIDATION",
                            "重新加载目标字段并修正映射"));
                }
                mappedTargets.add(mapping.targetField().toLowerCase(Locale.ROOT));
            }
            for (ColumnMetadata column : metadata.columns()) {
                if (!column.nullable() && !mappedTargets.contains(column.name().toLowerCase(Locale.ROOT))) {
                    issues.add(issue("VALIDATION_FAILED", "目标必填字段未映射: " + column.name(),
                            "fieldMappings", "TARGET_VALIDATION", "为目标必填字段补充映射"));
                }
            }
            if (request.writeMode() != null && "UPSERT".equals(request.writeMode())) {
                if (request.uniqueKeys() == null || request.uniqueKeys().isEmpty()) {
                    issues.add(issue("TARGET_UNIQUE_CONSTRAINT_MISSING", "UPSERT 必须配置唯一 Key",
                            "uniqueKeys", "TARGET_VALIDATION", "为 UPSERT 配置唯一 Key"));
                } else if (!adapter.hasUniqueConstraint(metadata, request.uniqueKeys())) {
                    issues.add(issue("TARGET_UNIQUE_CONSTRAINT_MISSING",
                            "目标表不存在与唯一 Key 匹配的唯一约束",
                            "uniqueKeys", "TARGET_VALIDATION", "在目标表上建立匹配的唯一约束"));
                }
            }
            if (!adapter.receiptTableExists(connection)) {
                issues.add(issue("SINK_NOT_READY",
                        "目标库缺少回执表 mic_sync_batch_receipt，需由 DBA 初始化后重试",
                        "receipt", "SINK_HANDSHAKE", "由 DBA 执行回执表初始化 SQL 后重试"));
            }
        } catch (Exception ex) {
            issues.add(issue("DATABASE_CONNECTION_FAILED",
                    "Target 预检失败: " + safeMessage(ex),
                    "sink", "TARGET_CONFIGURATION", "检查 Sink 连接配置与目标表后重试"));
        }
        boolean valid = issues.stream()
                .noneMatch(issue -> "BLOCKING".equals(issue.severity()));
        return ResponseEntity.ok(new AgentProtocol.TargetPreflightResponse(valid, issues));
    }

    /** Sink 令牌掩码状态（供控制台多端总览）。 */
    @GetMapping("/sink-token")
    public AgentProtocol.SinkTokenInfo sinkToken() {
        return new AgentProtocol.SinkTokenInfo(
                sinkTokenService.display().isPresent(),
                sinkTokenService.display().orElse(""));
    }

    private String selfEndpointId(DatabaseRole role) {
        return role == DatabaseRole.SOURCE
                ? DatabaseConfigService.SELF_SOURCE_ENDPOINT_ID
                : DatabaseConfigService.SELF_SINK_ENDPOINT_ID;
    }

    private Optional<DatabaseRole> parseRole(String role) {
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DatabaseRole.valueOf(role.toUpperCase()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ApiError(
                ErrorCode.VALIDATION_FAILED.name(), message, UUID.randomUUID().toString(), Map.of()));
    }

    private AgentProtocol.DataSourceInfo toDataSourceInfo(DatabaseConfig config) {
        return new AgentProtocol.DataSourceInfo(
                config.id(), config.name(), config.role().name(), config.databaseType().name(),
                config.jdbcUrl(), config.username(), config.driverType());
    }

    private AgentProtocol.PreflightIssue issue(String code, String message, String field,
                                               String stage, String suggestedAction) {
        return new AgentProtocol.PreflightIssue(
                "BLOCKING", code, message, field, stage, suggestedAction);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    private DataSourceResponse toResponse(DatabaseConfig config) {
        return new DataSourceResponse(
                config.id(),
                config.endpointId(),
                config.name(),
                config.role().name(),
                config.databaseType().name(),
                config.jdbcUrl(),
                config.username(),
                config.driverType(),
                config.createdAt() == null ? null : config.createdAt().toString(),
                config.updatedAt() == null ? null : config.updatedAt().toString());
    }

    /** 创建/更新/测试请求体。 */
    public record DataSourceRequest(
            String id,
            String role,
            String name,
            String product,
            String jdbcUrl,
            String username,
            String password,
            String driverType) {
    }

    /** 数据源响应（脱敏，不含密码）。 */
    public record DataSourceResponse(
            String id,
            String endpointId,
            String name,
            String role,
            String product,
            String jdbcUrl,
            String username,
            String driverType,
            String createdAt,
            String updatedAt) {
    }
}
