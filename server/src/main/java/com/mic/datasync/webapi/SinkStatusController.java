package com.mic.datasync.webapi;

import com.mic.datasync.sink.SinkHandshakeService;
import com.mic.datasync.sink.SinkAuthCheckService;
import com.mic.datasync.sink.SinkAuthCheckService.SinkAuthCheck;
import com.mic.datasync.sink.SinkTokenService;
import com.mic.datasync.sink.SourceSinkTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Sink 状态管理接口（管理员 Session 认证）。
 */
@RestController
@RequestMapping("/api/v1/sink")
public class SinkStatusController {

    private final SinkHandshakeService handshakeService;
    private final SinkTokenService tokenService;
    private final SourceSinkTokenService sourceTokenService;
    private final SinkAuthCheckService sinkAuthCheckService;

    public SinkStatusController(SinkHandshakeService handshakeService,
                                SinkTokenService tokenService,
                                SourceSinkTokenService sourceTokenService,
                                SinkAuthCheckService sinkAuthCheckService) {
        this.handshakeService = handshakeService;
        this.tokenService = tokenService;
        this.sourceTokenService = sourceTokenService;
        this.sinkAuthCheckService = sinkAuthCheckService;
    }

    /** Sink 状态（管理员视角，含就绪与批次限制）。 */
    @GetMapping("/status")
    public SinkHandshakeService.HandshakeResponse status() {
        return handshakeService.handshake();
    }

    /** Token 掩码展示（不回显明文；未配置时返回空掩码，不抛异常）。 */
    @GetMapping("/token")
    public ResponseEntity<?> token() {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("configured", tokenService.display().isPresent());
        body.put("display", tokenService.display().orElse(""));
        return ResponseEntity.ok(body);
    }

    /** 轮换 Sink Token（旧 Token 立即失效）。 */
    @PostMapping("/token/rotate")
    public ResponseEntity<?> rotate() {
        String generated = tokenService.generateAndSave();
        return ResponseEntity.ok(Map.of(
                "display", tokenService.display().orElse(null),
                "generated", generated));
    }

    /** Source 端令牌状态（掩码展示）。 */
    @GetMapping("/source-token")
    public ResponseEntity<?> sourceToken() {
        boolean configured = sourceTokenService.configuredFromDb();
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("configured", configured);
        body.put("source", configured ? "DB" : "CONFIG");
        body.put("display", configured ? sourceTokenService.display().orElse("") : "");
        return ResponseEntity.ok(body);
    }

    /** 保存 Source 端令牌（立即生效，无需重启）。 */
    @PutMapping("/source-token")
    public ResponseEntity<?> saveSourceToken(@RequestBody SourceTokenRequest request) {
        try {
            sourceTokenService.save(request.token());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "VALIDATION_FAILED",
                    "message", ex.getMessage(),
                    "requestId", java.util.UUID.randomUUID().toString(),
                    "details", java.util.Map.of()));
        }
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("configured", true);
        body.put("source", "DB");
        body.put("display", sourceTokenService.display().orElse(""));
        return ResponseEntity.ok(body);
    }

    /** 清除 Source 端令牌，回退到部署配置。 */
    @DeleteMapping("/source-token")
    public ResponseEntity<Void> clearSourceToken() {
        sourceTokenService.clear();
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /** Source 端令牌请求体。 */
    public record SourceTokenRequest(String token) {
    }

    /** 检查 Source 端与 Sink 端的批次认证。 */
    @PostMapping("/auth-check")
    public SinkAuthCheck authCheck(@RequestBody(required = false) SinkAuthRequest request) {
        String sinkUrl = request == null ? null : request.sinkUrl();
        return sinkAuthCheckService.check(sinkUrl);
    }

    /** 批次认证检查请求体。 */
    public record SinkAuthRequest(String sinkUrl) {
    }
}
