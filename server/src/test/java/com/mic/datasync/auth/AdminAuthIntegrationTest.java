package com.mic.datasync.auth;

import com.mic.datasync.instance.InstanceService;
import com.mic.datasync.shared.id.Identifiers;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理员认证与实例身份集成测试。
 *
 * <p>登录请求使用 spring-security-test 的 {@code with(csrf())} 提供合法 CSRF Token
 * （CSRF 拦截机制本身由 {@link #postWithoutCsrfTokenReturns403} 单独验证）；
 * 真实浏览器登录流程（Cookie + X-XSRF-TOKEN）由部署冒烟脚本覆盖。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InstanceService instanceService;

    private static final String USERNAME = "admin";
    private static final String PASSWORD = "admin123";

    @Test
    void unauthenticatedAccessToInstanceApiReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/instance"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithValidCredentialsEstablishesSession() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andReturn();

        // 登录请求创建的 Session 中应保存认证上下文
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // 携带同一会话访问受保护的管理 API
        mockMvc.perform(get("/api/v1/instance")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").isNotEmpty())
                .andExpect(jsonPath("$.startupId").isNotEmpty())
                .andExpect(jsonPath("$.roles").value("source,sink"))
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.readiness").value("READY"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}")
                        .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void postWithoutCsrfTokenReturns403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfEndpointReturnsTokenAndCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        Cookie xsrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).isNotNull();
        assertThat(xsrfCookie.getValue()).isNotBlank();
    }

    @Test
    void instanceIdIsStableAcrossInitializations() {
        Identifiers.InstanceId first = instanceService.ensureInitialized();
        Identifiers.InstanceId second = instanceService.ensureInitialized();
        assertThat(second).isEqualTo(first);
    }
}
