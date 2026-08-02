package com.mic.datasync.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.shared.error.ErrorCode;
import com.mic.datasync.webapi.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.nio.charset.StandardCharsets;

/**
 * 管理 API 安全配置。
 *
 * <ul>
 *   <li>管理 API 使用 Cookie Session（JSESSIONID）；</li>
 *   <li>非 GET 请求启用 CSRF，Token 通过 XSRF-TOKEN Cookie + X-XSRF-TOKEN 请求头传递；</li>
 *   <li>SPA 静态资源、健康检查、登录与 CSRF 端点允许匿名访问；</li>
 *   <li>Sink 数据接收链路使用 Sink Token 认证，不复用管理员 Session（后续任务实现）。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                // CSRF：Cookie 存储，前端读取 XSRF-TOKEN 并以请求头回传。
                // 使用非 XOR 的请求处理器，前端可原样回传 Cookie 中的 Token。
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Sink 外部链路使用 Bearer Token 认证（无 Cookie），跳过 CSRF
                        // Agent 链路使用 Sink 访问令牌认证（无 Cookie），跳过 CSRF
                        .ignoringRequestMatchers("/api/v1/sink/handshake", "/data/**", "/api/v1/agent/**"))
                .authorizeHttpRequests(auth -> auth
                        // 健康检查、系统 ping、CSRF 端点公开
                        .requestMatchers("/actuator/**", "/api/v1/system/ping", "/api/v1/auth/csrf").permitAll()
                        // 登录端点公开（CSRF 仍然生效）
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // Sink 数据接收与握手：由 SinkTokenFilter 做 Bearer 认证，不复用管理员 Session
                        .requestMatchers("/api/v1/sink/handshake", "/data/**").permitAll()
                        // Agent API：由 SinkTokenFilter 做 Sink 访问令牌认证，不复用管理员 Session
                        .requestMatchers("/api/v1/agent/**").permitAll()
                        // 管理 API 均需登录
                        .requestMatchers("/api/**").authenticated()
                        // SPA 静态资源与前端路由回退公开（认证由前端 JS 控制）
                        .anyRequest().permitAll())
                // 未登录访问管理 API：统一返回 401 JSON
                .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint(objectMapper)))
                // 管理 API 使用 JSON 登录，禁用表单与 Basic
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // 退出由 /api/v1/auth/logout 自定义处理
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 自适应哈希（BCrypt），用于管理员密码存储与校验
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    /**
     * REST 风格的未认证入口：返回 401 与统一错误体。
     */
    @Bean
    public AuthenticationEntryPoint restAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(objectMapper.writeValueAsString(ApiError.of(ErrorCode.UNAUTHORIZED)));
        };
    }
}
