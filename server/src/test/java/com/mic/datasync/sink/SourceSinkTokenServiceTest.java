package com.mic.datasync.sink;

import com.mic.datasync.storage.secret.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SourceSinkTokenServiceTest {

    @Autowired
    private SourceSinkTokenService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SecretCipher secretCipher;

    @BeforeEach
    void clearTable() {
        jdbcTemplate.update("DELETE FROM source_sink_token");
    }

    @Test
    void saveStoresEncryptedTokenAndResolvesIt() {
        service.save("mic_abcdef1234");

        assertThat(service.currentToken()).contains("mic_abcdef1234");
        assertThat(service.resolve()).isEqualTo("mic_abcdef1234");
        assertThat(service.display()).contains("mic_****1234");
        assertThat(service.configuredFromDb()).isTrue();
    }

    @Test
    void clearFallsBackToConfiguredToken() {
        SourceSinkTokenService custom =
                new SourceSinkTokenService(jdbcTemplate, secretCipher, "env-token", null, null);

        assertThat(custom.resolve()).isEqualTo("env-token");
        custom.save("mic_newtoken");
        assertThat(custom.resolve()).isEqualTo("mic_newtoken");
        custom.clear();
        assertThat(custom.resolve()).isEqualTo("env-token");
    }
}
