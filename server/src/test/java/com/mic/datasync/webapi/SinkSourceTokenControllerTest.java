package com.mic.datasync.webapi;

import com.mic.datasync.sink.SinkTokenService;
import com.mic.datasync.sink.SourceSinkTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SinkSourceTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SinkTokenService sinkTokenService;

    @Autowired
    private SourceSinkTokenService sourceTokenService;

    @BeforeEach
    void clearTable() {
        jdbcTemplate.update("DELETE FROM source_sink_token");
        jdbcTemplate.update("DELETE FROM sink_token");
    }

    @Test
    void putGetDeleteRoundTrip() throws Exception {
        mockMvc.perform(put("/api/v1/sink/source-token")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"mic_abcdef1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.source").value("DB"))
                .andExpect(jsonPath("$.display").value("mic_****1234"));

        mockMvc.perform(get("/api/v1/sink/source-token")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.source").value("DB"));

        mockMvc.perform(delete("/api/v1/sink/source-token")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/sink/source-token")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.source").value("CONFIG"));
    }

    @Test
    void blankTokenIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/sink/source-token")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authCheckComparesSourceAndSinkTokens() throws Exception {
        String token = sinkTokenService.generateAndSave();
        sourceTokenService.save(token);

        mockMvc.perform(post("/api/v1/sink/auth-check")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.sourceFromDb").value(true))
                .andExpect(jsonPath("$.sinkMasked").value(org.hamcrest.Matchers.containsString("mic_****")));
    }
}
