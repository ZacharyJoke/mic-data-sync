package com.mic.datasync.sink;

import com.mic.datasync.sink.SinkAuthCheckService.SinkAuthCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SinkAuthCheckServiceTest {

    @Autowired
    private SinkAuthCheckService service;

    @Autowired
    private SinkTokenService sinkTokenService;

    @Autowired
    private SourceSinkTokenService sourceTokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.update("DELETE FROM source_sink_token");
        jdbcTemplate.update("DELETE FROM sink_token");
    }

    @Test
    void matchingTokenPassesCheckWithoutHandshake() {
        String token = sinkTokenService.generateAndSave();
        sourceTokenService.save(token);

        SinkAuthCheck check = service.check(null);

        assertThat(check.ok()).isTrue();
        assertThat(check.sourceFromDb()).isTrue();
        assertThat(check.sinkMasked()).contains("mic_****");
        assertThat(check.handshake()).isNull();
    }

    @Test
    void mismatchedTokenFailsCheck() {
        sinkTokenService.generateAndSave();
        sourceTokenService.save("mic_differenttoken");

        assertThat(service.check(null).ok()).isFalse();
    }
}
