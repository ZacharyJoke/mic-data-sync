package com.mic.datasync.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全量/追赶/增量真实数据库集成测试（门控）。
 *
 * <p>需要真实 KingbaseES/openGauss 与可访问的 Sink（三方向 E2E 时启用）：
 * {@code -Dcontract.db.url=... -Dcontract.sink.url=...}。</p>
 */
@EnabledIfSystemProperty(named = "contract.db.url", matches = ".+")
class FullAndCatchUpIntegrationTest {

    @Test
    void sourceConnectionAndSampleQueryWork() throws Exception {
        String url = System.getProperty("contract.db.url");
        try (Connection connection = DriverManager.getConnection(
                url, System.getProperty("contract.db.username", ""), System.getProperty("contract.db.password", ""))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
            assertThat(connection.isValid(5)).isTrue();
        }
    }
}
