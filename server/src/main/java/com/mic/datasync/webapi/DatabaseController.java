package com.mic.datasync.webapi;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.DatabaseType;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * 数据库配置接口（需管理员登录）。
 *
 * <p>保留按角色操作默认档案的兼容端点；多档案管理请使用
 * {@code /api/v1/data-sources}。所有响应都不返回密码，密码字段只支持覆盖。</p>
 */
@RestController
@RequestMapping("/api/v1/database")
public class DatabaseController {

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;

    public DatabaseController(DatabaseConfigService configService, ConnectionFactory connectionFactory) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
    }

    /** 查询指定角色配置（脱敏，不含密码）。 */
    @GetMapping("/{role}")
    public ResponseEntity<?> get(@PathVariable String role) {
        Optional<DatabaseRole> parsed = parseRole(role);
        if (parsed.isEmpty()) {
            return badRequest("角色只能是 SOURCE 或 SINK");
        }
        Optional<DatabaseConfig> config = configService.getDefault(parsed.get());
        return config.map(c -> ResponseEntity.ok(toResponse(c)))
                .orElseGet(() -> ResponseEntity.ok(new DatabaseConfigResponse(
                        null, null, parsed.get().name(), null, null, null, null, null, null, false)));
    }

    /** 保存指定角色配置（密码为空表示保留原值）。 */
    @PutMapping("/{role}")
    public ResponseEntity<?> save(@PathVariable String role, @RequestBody DatabaseConfigRequest request) {
        Optional<DatabaseRole> parsed = parseRole(role);
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
            DatabaseConfig existing = configService.getDefault(parsed.get()).orElse(null);
            DatabaseConfig saved = existing == null
                    ? configService.create(selfEndpointId(parsed.get()), defaultId(parsed.get()), null, type,
                            request.jdbcUrl(), request.username(), request.password(), request.driverType())
                    : configService.update(existing.id(), null, type,
                            request.jdbcUrl(), request.username(), request.password(), request.driverType());
            return ResponseEntity.ok(toResponse(saved));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    /** 测试连接（使用请求中的配置，不保存）。 */
    @PostMapping("/{role}/test")
    public ResponseEntity<?> test(@PathVariable String role, @RequestBody DatabaseConfigRequest request) {
        Optional<DatabaseRole> parsed = parseRole(role);
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
        // 密码为空时回退到已保存配置的密码（前端"只覆盖不回显"场景）
        if (password.isBlank()) {
            Optional<DatabaseConfig> existing = configService.getDefault(parsed.get());
            if (existing.isPresent()) {
                password = existing.get().password();
            }
        }
        DatabaseConfig probe = new DatabaseConfig(null, null, null, parsed.get(), type, request.jdbcUrl(),
                request.username(), password, request.driverType(), null, null);
        return ResponseEntity.ok(connectionFactory.testConnection(probe));
    }

    /** 删除指定角色配置。 */
    @DeleteMapping("/{role}")
    public ResponseEntity<?> delete(@PathVariable String role) {
        Optional<DatabaseRole> parsed = parseRole(role);
        if (parsed.isEmpty()) {
            return badRequest("角色只能是 SOURCE 或 SINK");
        }
        Optional<DatabaseConfig> existing = configService.getDefault(parsed.get());
        if (existing.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        try {
            configService.delete(existing.get().id());
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(
                    "VALIDATION_FAILED", ex.getMessage()));
        }
    }

    private Optional<DatabaseRole> parseRole(String role) {
        try {
            return Optional.of(DatabaseRole.valueOf(role.toUpperCase()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String selfEndpointId(DatabaseRole role) {
        return role == DatabaseRole.SOURCE
                ? DatabaseConfigService.SELF_SOURCE_ENDPOINT_ID
                : DatabaseConfigService.SELF_SINK_ENDPOINT_ID;
    }

    private String defaultId(DatabaseRole role) {
        return role == DatabaseRole.SOURCE ? "source-default" : "sink-default";
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ApiError(
                ErrorCode.VALIDATION_FAILED.name(), message, java.util.UUID.randomUUID().toString(), java.util.Map.of()));
    }

    private ApiError errorBody(String code, String message) {
        return new ApiError(code, message, java.util.UUID.randomUUID().toString(), java.util.Map.of());
    }

    private DatabaseConfigResponse toResponse(DatabaseConfig config) {
        return new DatabaseConfigResponse(
                config.id(),
                config.name(),
                config.role().name(),
                config.databaseType().name(),
                config.jdbcUrl(),
                config.username(),
                config.driverType(),
                config.createdAt() == null ? null : config.createdAt().toString(),
                config.updatedAt() == null ? null : config.updatedAt().toString(),
                true);
    }

    /** 保存/测试请求体。 */
    public record DatabaseConfigRequest(
            String product,
            String jdbcUrl,
            String username,
            String password,
            String driverType) {
    }

    /** 配置响应（脱敏，不含密码）。 */
    public record DatabaseConfigResponse(
            String id,
            String name,
            String role,
            String product,
            String jdbcUrl,
            String username,
            String driverType,
            String createdAt,
            String updatedAt,
            boolean configured) {
    }
}
