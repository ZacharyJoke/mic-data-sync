package com.mic.datasync.webapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void summaryReturnsDashboardShape() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.configured").isBoolean())
                .andExpect(jsonPath("$.source.healthy").isBoolean())
                .andExpect(jsonPath("$.sink.configured").isBoolean())
                .andExpect(jsonPath("$.instance.readiness").value("READY"))
                .andExpect(jsonPath("$.instance.roles").isString())
                .andExpect(jsonPath("$.enabledTaskCount").isNumber())
                .andExpect(jsonPath("$.activeRunCount").isNumber())
                .andExpect(jsonPath("$.unresolvedFailureCount").isNumber())
                .andExpect(jsonPath("$.recentRuns").isArray())
                .andExpect(jsonPath("$.alerts").isArray());
    }
}
