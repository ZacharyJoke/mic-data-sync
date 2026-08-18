package com.mic.datasync.webapi;

import com.mic.datasync.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理员认证接口。
 *
 * <ul>
 *   <li>POST /login：JSON 登录，成功后建立 Cookie Session；</li>
 *   <li>POST /logout：失效当前会话；</li>
 *   <li>GET /me：返回当前登录用户（前端会话恢复用）；</li>
 *   <li>GET /csrf：获取 CSRF Token（登录前必须先获取）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();
    private final String csrfCookieName;

    public AuthController(
            AuthenticationManager authenticationManager,
            @Value("${mic.sync.security.csrf-cookie-name:XSRF-TOKEN}") String csrfCookieName) {
        this.authenticationManager = authenticationManager;
        this.csrfCookieName = csrfCookieName;
    }

    /** 获取 CSRF Token，同时显式通过响应 Cookie 下发 XSRF-TOKEN（与 CsrfFilter 校验口径一致）。 */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken, HttpServletResponse response) {
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie(csrfCookieName, csrfToken.getToken());
        cookie.setPath("/");
        cookie.setHttpOnly(false);
        response.addCookie(cookie);
        // 返回 Cookie 名，前端据此动态读取（支持多实例不同 CSRF Cookie 名）
        return Map.of("token", csrfToken.getToken(), "csrfCookieName", csrfCookieName);
    }

    /** 管理员登录。 */
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            // 显式保存 SecurityContext 到 Session，保证后续请求可恢复认证状态
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            HttpSession session = httpRequest.getSession(true);
            new HttpSessionSecurityContextRepository().saveContext(context, httpRequest, httpResponse);
            return ResponseEntity.ok(Map.of(
                    "username", authentication.getName(),
                    "roles", authentication.getAuthorities()));
        } catch (BadCredentialsException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of(ErrorCode.UNAUTHORIZED));
        }
    }

    /** 退出登录。 */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        logoutHandler.logout(request, response, authentication);
        return ResponseEntity.ok().build();
    }

    /** 当前登录用户信息。 */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        return Map.of(
                "username", authentication.getName(),
                "roles", authentication.getAuthorities());
    }

    /** 登录请求体。 */
    public record LoginRequest(String username, String password) {
    }
}
