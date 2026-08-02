package com.mic.datasync.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.sink.SinkTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Sink 数据接收/握手认证过滤器。
 *
 * <p>Sink 链路与 Agent 管理链路统一使用 {@code Authorization: Bearer <token>}
 * （Sink 访问令牌），不复用管理员 Session；未携带或错误的 Token 返回 401
 * （SINK_AUTHENTICATION_FAILED）。</p>
 */
@Component
public class SinkTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SinkTokenService tokenService;
    private final ObjectMapper objectMapper;

    public SinkTokenFilter(SinkTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!isSinkPath(uri)) {
            filterChain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response);
            return;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!tokenService.validate(token)) {
            writeUnauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isSinkPath(String uri) {
        // 外部 Sink 链路：握手 + 数据接收；Agent 管理链路使用同一 Sink 访问令牌
        return uri.startsWith("/data/") || uri.equals("/api/v1/sink/handshake")
                || uri.startsWith("/api/v1/agent/");
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Map.of(
                "code", "SINK_AUTHENTICATION_FAILED",
                "message", "Sink 认证失败：Token 缺失或无效",
                "requestId", UUID.randomUUID().toString(),
                "details", Map.of())));
    }
}
