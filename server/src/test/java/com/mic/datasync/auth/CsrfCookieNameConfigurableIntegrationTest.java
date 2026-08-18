package com.mic.datasync.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSRF Cookie 名可配置验证：同主机多实例通过不同 {@code mic.sync.security.csrf-cookie-name}
 * 隔离同名 Cookie，避免浏览器同域共享同名 Cookie 导致会话/CSRF 互顶。
 */
@SpringBootTest(properties = "mic.sync.security.csrf-cookie-name=TEST-CSRF")
@AutoConfigureMockMvc
class CsrfCookieNameConfigurableIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void csrfEndpointUsesConfiguredCookieName() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.csrfCookieName").value("TEST-CSRF"))
                .andReturn();

        Cookie xsrfCookie = result.getResponse().getCookie("TEST-CSRF");
        assertThat(xsrfCookie).isNotNull();
        assertThat(xsrfCookie.getValue()).isNotBlank();
        assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNull();
    }
}
