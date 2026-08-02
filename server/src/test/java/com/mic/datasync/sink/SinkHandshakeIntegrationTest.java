package com.mic.datasync.sink;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sink 握手与 Token 认证集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SinkHandshakeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SinkTokenService tokenService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 生成全新 Token（轮换）。 */
    private String freshToken() {
        return tokenService.generateAndSave();
    }

    @Test
    void handshakeWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/sink/handshake"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SINK_AUTHENTICATION_FAILED"));
    }

    @Test
    void handshakeWithWrongTokenReturns401() throws Exception {
        freshToken();
        mockMvc.perform(post("/api/v1/sink/handshake")
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void handshakeWithValidTokenReturnsIdentityAndLimits() throws Exception {
        String token = freshToken();
        mockMvc.perform(post("/api/v1/sink/handshake")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sinkInstanceId").isNotEmpty())
                .andExpect(jsonPath("$.startupId").isNotEmpty())
                .andExpect(jsonPath("$.protocolVersion").value(1))
                .andExpect(jsonPath("$.batchLimits.maxRowsPerBatch").value(1000))
                .andExpect(jsonPath("$.batchLimits.maxPayloadBytes").value(16777216))
                // 未配置 Sink 数据库：NOT_READY
                .andExpect(jsonPath("$.capabilityStatus").value("NOT_READY"));
    }

    @Test
    void rotatedTokenInvalidatesOldToken() throws Exception {
        String old = freshToken();
        String current = tokenService.generateAndSave();
        assertThat(old).isNotEqualTo(current);

        mockMvc.perform(post("/api/v1/sink/handshake")
                        .header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/sink/handshake")
                        .header("Authorization", "Bearer " + current))
                .andExpect(status().isOk());
    }

    @Test
    void tokenMaskNeverExposesPlaintext() throws Exception {
        String token = freshToken();
        mockMvc.perform(get("/api/v1/sink/token")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.display", Matchers.startsWith("mic_****")))
                .andExpect(jsonPath("$.display", Matchers.not(Matchers.equalTo(token))));
    }

    @Test
    void sinkTokenEndpointRequiresAdminSession() throws Exception {
        mockMvc.perform(get("/api/v1/sink/token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenEndpointWithoutConfiguredTokenReturns200() throws Exception {
        // 清空 token 表模拟未配置场景
        jdbcTemplate.update("DELETE FROM sink_token");
        mockMvc.perform(get("/api/v1/sink/token")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.display").value(""));
        // 恢复：生成一个 token 避免影响其他用例
        tokenService.generateAndSave();
    }

    @Test
    void rotateEndpointReturnsNewMask() throws Exception {
        freshToken();
        String displayBefore = tokenService.display().orElse(null);
        mockMvc.perform(post("/api/v1/sink/token/rotate")
                        .with(user("admin").roles("ADMIN"))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display", Matchers.startsWith("mic_****")));
        assertThat(tokenService.display().orElse(null)).isNotEqualTo(displayBefore);
    }
}
