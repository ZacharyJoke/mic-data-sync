package com.mic.datasync.webapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端注册表、数据源档案与 Agent（Sink 令牌认证）接口集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EndpointDataSourceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.mic.datasync.sink.SinkTokenService sinkTokenService;

    private static final org.springframework.test.web.servlet.request.RequestPostProcessor ADMIN =
            user("admin").roles("ADMIN");

    @Test
    void selfEndpointsAreListedAndProtected() throws Exception {
        mockMvc.perform(get("/api/v1/endpoints").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'self-source')].isSelf").value(true))
                .andExpect(jsonPath("$[?(@.id == 'self-sink')].isSelf").value(true));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/endpoints/self-source").with(ADMIN).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void createAndListDataSourceUnderSelfEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/data-sources").with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endpointId":"self-source","name":"集成测试源",
                                 "product":"OPEN_GAUSS","jdbcUrl":"jdbc:opengauss://db:5432/sync",
                                 "username":"sync_user","password":"S3cret!p@ss","driverType":"opengauss"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endpointId").value("self-source"))
                .andExpect(jsonPath("$.name").value("集成测试源"));

        mockMvc.perform(get("/api/v1/data-sources?endpointId=self-source").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '集成测试源')].username").value("sync_user"));
    }

    @Test
    void agentProbeRequiresSinkToken() throws Exception {
        mockMvc.perform(post("/api/v1/agent/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SINK_AUTHENTICATION_FAILED"));
    }

    @Test
    void sinkTokenValidatesAgentProbe() throws Exception {
        String token = sinkTokenService.generateAndSave();

        mockMvc.perform(post("/api/v1/agent/probe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").isNotEmpty())
                .andExpect(jsonPath("$.roles").isArray());
    }

    @Test
    void sinkTokenStatusIsExposedAsMaskedForSelfSink() throws Exception {
        mockMvc.perform(get("/api/v1/endpoints/self-sink/sink-token").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").isBoolean())
                .andExpect(jsonPath("$.display").isString());
    }

    @Test
    void selfSinkAuthCheckPassesWithLocalSinkToken() throws Exception {
        sinkTokenService.generateAndSave();
        mockMvc.perform(post("/api/v1/endpoints/self-sink/auth-check").with(ADMIN).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.message").value("认证通过"));
    }
}
