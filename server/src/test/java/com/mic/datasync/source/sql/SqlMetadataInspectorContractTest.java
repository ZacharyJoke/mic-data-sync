package com.mic.datasync.source.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 字段探查真实数据库契约测试。
 *
 * <p>需要真实 KingbaseES/openGauss 环境，通过系统属性提供连接（三方向 E2E 时启用）：</p>
 * <pre>
 * -Dcontract.db.url=jdbc:... -Dcontract.db.username=... -Dcontract.db.password=...
 * </pre>
 * 本地无真实数据库时整个类跳过，不使用 H2 代替发布结论。
 */
@EnabledIfSystemProperty(named = "contract.db.url", matches = ".+")
class SqlMetadataInspectorContractTest {

    private final SqlMetadataInspector inspector = new SqlMetadataInspector();

    @Test
    void inspectsColumnsFromRealDatabase() throws Exception {
        String url = System.getProperty("contract.db.url");
        String username = System.getProperty("contract.db.username", "");
        String password = System.getProperty("contract.db.password", "");

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            SqlMetadataInspector.InspectionResult result = inspector.inspect(connection,
                    "SELECT 1 AS one, 'x' AS text_col");

            assertThat(result.columns()).isNotEmpty();
            List<String> names = result.columns().stream()
                    .map(SqlMetadataInspector.ResultColumn::name)
                    .collect(Collectors.toList());
            assertThat(names).contains("one");
            assertThat(result.structureFingerprint()).isNotBlank();
        }
    }
}
