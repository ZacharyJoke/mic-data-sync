package com.mic.datasync.webapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SPA 回退行为测试。
 *
 * <p>前端路由路径（如 /tasks）应回退到 /index.html；API 与 Actuator 路径
 * 不允许进入 SPA 回退，必须保持原有的 200/404 语义。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SpaForwardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootReturnsIndexHtml() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    void spaRouteForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/tasks"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void nestedSpaRouteForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/runs/run-123"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void apiPathIsNotForwarded() throws Exception {
        mockMvc.perform(get("/api/v1/system/ping")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(forwardedUrl(null));
    }

    @Test
    void unknownApiPathReturns404WithoutForward() throws Exception {
        mockMvc.perform(get("/api/v1/not-exist")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(forwardedUrl(null));
    }

    @Test
    void actuatorHealthIsNotForwarded() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl(null));
    }
}
