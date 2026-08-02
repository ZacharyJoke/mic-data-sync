package com.mic.datasync.webapi;

import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.endpoint.EndpointRecord;
import com.mic.datasync.endpoint.EndpointService;
import com.mic.datasync.endpoint.EndpointService.ProbeResult;
import com.mic.datasync.endpoint.AgentClient;
import com.mic.datasync.endpoint.AgentProtocol;
import com.mic.datasync.sink.SinkHandshakeService;
import com.mic.datasync.sink.SinkTokenService;
import com.mic.datasync.sink.SourceSinkTokenService;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.security.MessageDigest;

/**
 * 端管理接口（需管理员登录）。
 *
 * <p>v1：Source 端固定为当前实例；Sink 端可增删改查并探活。</p>
 */
@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

    private final EndpointService endpointService;
    private final SourceSinkTokenService sourceTokenService;
    private final SinkTokenService sinkTokenService;
    private final SinkHandshakeService handshakeService;
    private final AgentClient agentClient;

    public EndpointController(EndpointService endpointService,
                              SourceSinkTokenService sourceTokenService,
                              SinkTokenService sinkTokenService,
                              SinkHandshakeService handshakeService,
                              AgentClient agentClient) {
        this.endpointService = endpointService;
        this.sourceTokenService = sourceTokenService;
        this.sinkTokenService = sinkTokenService;
        this.handshakeService = handshakeService;
        this.agentClient = agentClient;
    }

    /** 列出端（可按角色过滤；不含管理令牌）。 */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String role) {
        if (role != null && !role.isBlank()) {
            Optional<DatabaseRole> parsed = parseRole(role);
            if (parsed.isEmpty()) {
                return badRequest("角色只能是 SOURCE 或 SINK");
            }
            return ResponseEntity.ok(endpointService.list(parsed.get()).stream().map(this::toResponse).toList());
        }
        return ResponseEntity.ok(endpointService.listAll().stream().map(this::toResponse).toList());
    }

    /** 端详情（不含管理令牌）。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        Optional<EndpointRecord> endpoint = endpointService.get(id);
        if (endpoint.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), "端不存在",
                    UUID.randomUUID().toString(), Map.of()));
        }
        return ResponseEntity.ok(toResponse(endpoint.get()));
    }

    /** 新增 Sink 端。 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody EndpointRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(toResponse(endpointService.create(
                            request.name(), request.baseUrl(), request.sinkToken())));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    /** 更新 Sink 端（令牌为空保留原值）。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody EndpointRequest request) {
        try {
            return ResponseEntity.ok(toResponse(endpointService.update(
                    id, request.name(), request.baseUrl(), request.sinkToken())));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    /** 删除 Sink 端；被数据源或任务引用时返回 409。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            if (endpointService.delete(id)) {
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), "端不存在",
                    UUID.randomUUID().toString(), Map.of()));
        } catch (IllegalStateException | IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), ex.getMessage(),
                    UUID.randomUUID().toString(), Map.of()));
        }
    }

    /** 探活并回填实例 ID / 状态。 */
    @PostMapping("/{id}/probe")
    public ResponseEntity<?> probe(@PathVariable String id) {
        try {
            ProbeResult result = endpointService.probe(id);
            return ResponseEntity.ok(Map.of(
                    "message", result.message(),
                    "endpoint", toResponse(result.endpoint())));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    /** 检查批次认证：Source 侧令牌与所选 Sink 端令牌比对（本地直接校验，远程下发 Agent）。 */
    @PostMapping("/{id}/auth-check")
    public ResponseEntity<?> authCheck(@PathVariable String id) {
        EndpointRecord endpoint = endpointService.get(id).orElse(null);
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), "端不存在",
                    UUID.randomUUID().toString(), Map.of()));
        }
        if (endpoint.role() != DatabaseRole.SINK) {
            return badRequest("仅 Sink 端支持批次认证检查");
        }
        String sourceToken = resolveSourceTokenForEndpoint(endpoint);
        if (sourceToken == null || sourceToken.isBlank()) {
            return authResult(false, "未配置 Source 端访问令牌",
                    sourceTokenService.display().orElse(""), "", null);
        }
        if (endpoint.isSelf()) {
            Optional<String> sinkToken = sinkTokenService.currentToken();
            boolean ok = sinkToken.isPresent() && MessageDigest.isEqual(
                    sourceToken.getBytes(StandardCharsets.UTF_8),
                    sinkToken.get().getBytes(StandardCharsets.UTF_8));
            String handshake = ok ? handshakeService.handshake().capabilityStatus() : null;
            return authResult(ok, ok ? "认证通过" : "认证失败",
                    maskToken(sourceToken), sinkTokenService.display().orElse(""), handshake);
        }
        String endpointSinkToken = endpoint.sinkToken();
        boolean ok = endpointSinkToken != null && !endpointSinkToken.isBlank()
                && MessageDigest.isEqual(
                        sourceToken.getBytes(StandardCharsets.UTF_8),
                        endpointSinkToken.getBytes(StandardCharsets.UTF_8));
        String handshake = null;
        if (ok) {
            int statusCode = agentClient.handshakeStatus(endpoint, sourceToken);
            handshake = statusCode > 0 ? "HTTP " + statusCode : "unreachable";
        }
        return authResult(ok, ok ? "认证通过" : "认证失败",
                maskToken(sourceToken), maskToken(endpointSinkToken), handshake);
    }

    private String resolveSourceTokenForEndpoint(EndpointRecord endpoint) {
        if (endpoint.isSelf()) {
            return sinkTokenService.currentToken().orElse(sourceTokenService.resolve());
        }
        if (endpoint.sinkToken() != null && !endpoint.sinkToken().isBlank()) {
            return endpoint.sinkToken();
        }
        return sourceTokenService.resolve();
    }

    private String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        int tail = Math.min(4, token.length() - 4);
        return token.substring(0, 4) + "****" + token.substring(token.length() - tail);
    }

    private ResponseEntity<Map<String, Object>> authResult(
            boolean ok, String message, String sourceDisplay, String sinkMasked, String handshake) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ok", ok);
        body.put("message", message);
        body.put("sourceDisplay", sourceDisplay);
        body.put("sinkMasked", sinkMasked);
        body.put("handshake", handshake);
        return ResponseEntity.ok(body);
    }

    /** 查询 Sink 端令牌掩码状态（本地直读，远程下发 Agent）。 */
    @GetMapping("/{id}/sink-token")
    public ResponseEntity<?> sinkToken(@PathVariable String id) {
        EndpointRecord endpoint = endpointService.get(id).orElse(null);
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), "端不存在",
                    UUID.randomUUID().toString(), Map.of()));
        }
        if (endpoint.role() != DatabaseRole.SINK) {
            return badRequest("仅 Sink 端有 Sink 令牌");
        }
        if (endpoint.isSelf()) {
            return ResponseEntity.ok(Map.of(
                    "configured", sinkTokenService.display().isPresent(),
                    "display", sinkTokenService.display().orElse("")));
        }
        try {
            AgentProtocol.SinkTokenInfo info = agentClient.getSinkToken(endpoint);
            return ResponseEntity.ok(Map.of(
                    "configured", info.configured(),
                    "display", info.display()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError(
                    ErrorCode.DATABASE_CONNECTION_FAILED.name(), ex.getMessage(),
                    UUID.randomUUID().toString(), Map.of()));
        }
    }

    /** 查询 Sink 端完整状态（数据库类型/就绪/批次限制/协议版本等）。 */
    @GetMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable String id) {
        EndpointRecord endpoint = endpointService.get(id).orElse(null);
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), "端不存在",
                    UUID.randomUUID().toString(), Map.of()));
        }
        if (endpoint.role() != DatabaseRole.SINK) {
            return badRequest("仅 Sink 端有状态信息");
        }
        if (endpoint.isSelf()) {
            return ResponseEntity.ok(handshakeService.handshake());
        }
        String token = endpoint.sinkToken();
        if (token == null || token.isBlank()) {
            return badRequest("未配置 Sink 访问令牌，请先在端管理中填写");
        }
        try {
            return ResponseEntity.ok(agentClient.handshake(endpoint, token));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiError(
                    ErrorCode.DATABASE_CONNECTION_FAILED.name(), ex.getMessage(),
                    UUID.randomUUID().toString(), Map.of()));
        }
    }

    private Optional<DatabaseRole> parseRole(String role) {
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

    private EndpointResponse toResponse(EndpointRecord endpoint) {
        return new EndpointResponse(
                endpoint.id(),
                endpoint.name(),
                endpoint.role().name(),
                endpoint.baseUrl(),
                endpoint.instanceId(),
                endpoint.isSelf(),
                endpoint.status(),
                endpoint.lastProbeAt() == null ? null : endpoint.lastProbeAt().toString(),
                endpoint.createdAt().toString(),
                endpoint.updatedAt().toString());
    }

    /** 创建/更新请求体。 */
    public record EndpointRequest(String name, String baseUrl, String sinkToken) {
    }

    /** 端响应（不含管理令牌）。 */
    public record EndpointResponse(
            String id,
            String name,
            String role,
            String baseUrl,
            String instanceId,
            boolean isSelf,
            String status,
            String lastProbeAt,
            String createdAt,
            String updatedAt) {
    }
}
