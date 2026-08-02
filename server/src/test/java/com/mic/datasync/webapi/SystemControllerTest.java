package com.mic.datasync.webapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SystemController} 接口测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void pingShouldReturn200AndExpectedStructure() throws Exception {
        mockMvc.perform(get("/api/v1/system/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("mic-data-sync"));
    }
}
