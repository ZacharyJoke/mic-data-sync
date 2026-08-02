package com.mic.datasync.webapi;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.endpoint.AgentClient;
import com.mic.datasync.endpoint.AgentProtocol;
import com.mic.datasync.endpoint.EndpointRecord;
import com.mic.datasync.endpoint.EndpointService;
import com.mic.datasync.shared.error.ErrorCode;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 控制台数据源管理接口（需管理员登录）。
 *
 * <p>数据源归属某个端：自身端直接在本地存取；远程端通过 Agent API 下发到
 * 所属端执行，本地仅保留目录镜像（不保存远程密码）。</p>
 */
@RestController
@RequestMapping("/api/v1/data-sources")
public class DataSourceController {

    private static final String REMOTE_CATALOG_PASSWORD = "remote-catalog";

    private final DatabaseConfigService configService;
    private final EndpointService endpointService;
    private final ConnectionFactory connectionFactory;
    private final AgentClient agentClient;

    public DataSourceController(DatabaseConfigService configService,
                                EndpointService endpointService,
                                ConnectionFactory connectionFactory,
                                AgentClient agentClient) {
        this.configService = configService;
        this.endpointService = endpointService;
        this.connectionFactory = connectionFactory;
        this.agentClient = agentClient;
    }

    /** 列出数据源（可按所属端过滤；不含密码）。 */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String endpointId,
                                  @RequestParam(required = false) String role) {
        if (endpointId != null && !endpointId.isBlank()) {
            EndpointRecord endpoint = endpointService.get(endpointId).orElse(null);
            if (endpoint == null) {
                return notFound("端不存在");
            }
            if (endpoint.isSelf()) {
                return ResponseEntity.ok(configService.list(endpointId).stream()
                        .map(config -> toResponse(config, endpointId))
                        .toList());
            }
            try {
                List<AgentProtocol.DataSourceInfo> items =
                        agentClient.listDataSources(endpoint, endpoint.role());
                return ResponseEntity.ok(items.stream()
                        .map(item -> new DataSourceResponse(
                                item.id(), endpointId, item.name(), item.role(), item.product(),
                                item.jdbcUrl(), item.username(), item.driverType(), null, null))
                        .toList());
            } catch (IllegalStateException ex) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError(
                        ErrorCode.DATABASE_CONNECTION_FAILED.name(), ex.getMessage(),
                        UUID.randomUUID().toString(), Map.of()));
            }
        }
        List<DataSourceResponse> all = configService.listAll().stream()
                .map(config -> toResponse(config, config.endpointId()))
                .toList();
        return ResponseEntity.ok(all);
    }

    /** 查询单个数据源档案（本地目录；不含密码）。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        Optional<DatabaseConfig> config = configService.get(id);
        if (config.isEmpty()) {
            return notFound("数据源不存在");
        }
        return ResponseEntity.ok(toResponse(config.get(), config.get().endpointId()));
    }

    /** 创建数据源档案（远程端通过 Agent 下发创建并回写本地目录）。 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody DataSourceRequest request) {
        EndpointRecord endpoint = endpointService.get(request.endpointId()).orElse(null);
        if (endpoint == null) {
            return badRequest("所属端不存在");
        }
        DatabaseType type;
        try {
            type = DatabaseType.valueOf(request.product());
        } catch (IllegalArgumentException ex) {
            return badRequest("数据库类型只能是 KINGBASE_ES 或 OPEN_GAUSS");
        }
        try {
            if (endpoint.isSelf()) {
                DatabaseConfig saved = configService.create(
                        endpoint.id(), null, request.name(), type, request.jdbcUrl(),
                        request.username(), request.password(), request.driverType());
                return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved, endpoint.id()));
            }
            String id = UUID.randomUUID().toString();
            agentClient.create(endpoint, endpoint.role(), id, request.name(), type.name(),
                    request.jdbcUrl(), request.username(), request.password(), request.driverType());
            DatabaseConfig saved = configService.create(
                    endpoint.id(), id, request.name(), type, request.jdbcUrl(),
                    request.username(), REMOTE_CATALOG_PASSWORD, request.driverType());
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved, endpoint.id()));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError(
                    ErrorCode.DATABASE_CONNECTION_FAILED.name(), ex.getMessage(),
                    UUID.randomUUID().toString(), Map.of()));
        }
    }

    /** 更新数据源档案（密码/名称为空保留原值；远程端同时下发更新）。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody DataSourceRequest request) {
        DatabaseConfig existing = configService.get(id).orElse(null);
        if (existing == null) {
            return notFound("数据源不存在");
        }
        EndpointRecord endpoint = endpointService.get(existing.endpointId()).orElse(null);
        if (endpoint == null) {
            return badRequest("所属端不存在");
        }
        DatabaseType type;
        try {
            type = DatabaseType.valueOf(request.product());
        } catch (IllegalArgumentException ex) {
            return badRequest("数据库类型只能是 KINGBASE_ES 或 OPEN_GAUSS");
        }
        try {
            if (!endpoint.isSelf()) {
                agentClient.update(endpoint, id, request.name(), type.name(), request.jdbcUrl(),
                        request.username(), request.password(), request.driverType());
            }
            DatabaseConfig saved = configService.update(
                    id, request.name(), type, request.jdbcUrl(), request.username(),
                    request.password(), request.driverType());
            return ResponseEntity.ok(toResponse(saved, endpoint.id()));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError(
                    ErrorCode.DATABASE_CONNECTION_FAILED.name(), ex.getMessage(),
                    UUID.randomUUID().toString(), Map.of()));
        }
    }

    /** 删除数据源档案（远程端同步删除）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        DatabaseConfig existing = configService.get(id).orElse(null);
        if (existing == null) {
            return notFound("数据源不存在");
        }
        EndpointRecord endpoint = endpointService.get(existing.endpointId()).orElse(null);
        if (endpoint == null) {
            return badRequest("所属端不存在");
        }
        try {
            if (!endpoint.isSelf()) {
                agentClient.delete(endpoint, id);
            }
            configService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), ex.getMessage(),
                    UUID.randomUUID().toString(), Map.of()));
        }
    }

    /** 连接测试：下发到所属端执行（密码为空回退到所属端已保存密码）。 */
    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestBody TestRequest request) {
        DatabaseConfig existing = request.id() == null ? null : configService.get(request.id()).orElse(null);
        String endpointId = existing != null ? existing.endpointId() : request.endpointId();
        EndpointRecord endpoint = endpointService.get(endpointId).orElse(null);
        if (endpoint == null) {
            return badRequest("所属端不存在");
        }
        DatabaseRole role = existing != null ? existing.role() : endpoint.role();
        DatabaseType type;
        try {
            type = DatabaseType.valueOf(request.product());
        } catch (IllegalArgumentException ex) {
            return badRequest("数据库类型只能是 KINGBASE_ES 或 OPEN_GAUSS");
        }
        String password = request.password() == null ? "" : request.password();
        if (!endpoint.isSelf()) {
            return ResponseEntity.ok(agentClient.test(
                    endpoint, role, request.id(), request.name(), type.name(), request.jdbcUrl(),
                    request.username(), password, request.driverType()));
        }
        if (password.isBlank() && existing != null) {
            password = existing.password();
        }
        DatabaseConfig probe = new DatabaseConfig(
                request.id(), endpoint.id(), request.name(), role, type, request.jdbcUrl(),
                request.username(), password, request.driverType(), null, null);
        return ResponseEntity.ok(connectionFactory.testConnection(probe));
    }

    private DataSourceResponse toResponse(DatabaseConfig config, String endpointId) {
        return new DataSourceResponse(
                config.id(),
                endpointId,
                config.name(),
                config.role().name(),
                config.databaseType().name(),
                config.jdbcUrl(),
                config.username(),
                config.driverType(),
                config.createdAt() == null ? null : config.createdAt().toString(),
                config.updatedAt() == null ? null : config.updatedAt().toString());
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ApiError(
                ErrorCode.VALIDATION_FAILED.name(), message, UUID.randomUUID().toString(), Map.of()));
    }

    private ResponseEntity<?> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                ErrorCode.VALIDATION_FAILED.name(), message, UUID.randomUUID().toString(), Map.of()));
    }

    /** 创建/更新请求体。 */
    public record DataSourceRequest(
            String endpointId,
            String name,
            String product,
            String jdbcUrl,
            String username,
            String password,
            String driverType) {
    }

    /** 测试请求体。 */
    public record TestRequest(
            String id,
            String endpointId,
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
