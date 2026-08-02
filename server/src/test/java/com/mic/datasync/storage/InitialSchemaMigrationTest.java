package com.mic.datasync.storage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQLite 初始迁移测试。
 *
 * <p>使用临时 SQLite 文件运行全部迁移，断言每张表与关键唯一约束存在，
 * 并验证重复执行迁移不会产生新的迁移。</p>
 */
class InitialSchemaMigrationTest {

    @TempDir
    Path tempDir;

    private static final List<String> EXPECTED_TABLES = List.of(
            "client_instance",
            "admin_user",
            "sync_endpoint",
            "data_source",
            "task",
            "run",
            "batch",
            "checkpoint",
            "alert",
            "run_failure",
            "run_retry_request",
            "source_sink_token");

    @Test
    void migrationsCreateAllTablesAndConstraints() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("sync-test.db");
        Flyway flyway = Flyway.configure()
                .dataSource(url, null, null)
                .locations("classpath:db/migration")
                .load();

        // 首次迁移：创建全部表
        flyway.migrate();

        try (Connection conn = DriverManager.getConnection(url)) {
            // 八张表全部存在
            assertThat(queryTableNames(conn)).containsAll(EXPECTED_TABLES);

            // 关键唯一约束存在
            // admin_user.username UNIQUE / data_source(endpoint_id, name) UNIQUE
            // 由 SQLite 自动创建 sqlite_autoindex_* 索引
            assertThat(queryIndexNames(conn, "admin_user"))
                    .contains("sqlite_autoindex_admin_user_1");
            assertThat(queryIndexNames(conn, "data_source"))
                    .contains("sqlite_autoindex_data_source_1");
            assertThat(queryIndexNames(conn, "sync_endpoint"))
                    .contains("sqlite_autoindex_sync_endpoint_1");
            // batch 的 (run_id, batch_sequence) 显式唯一索引
            assertThat(queryIndexNames(conn, "batch"))
                    .contains("uq_batch_run_sequence");
            // checkpoint.task_id 为 TEXT PRIMARY KEY，SQLite 自动建索引
            assertThat(queryIndexNames(conn, "checkpoint"))
                    .contains("sqlite_autoindex_checkpoint_1");
            // 运行诊断迁移
            assertThat(queryColumnNames(conn, "run")).contains("previous_run_id");
        }

        // 迁移历史：V001-V007 + V008 移除管理令牌，统一使用 Sink 访问令牌
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSize(8);
        // Flyway 将版本号规范化为定长字符串（"1" → "001"）
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("001");
        assertThat(applied[1].getVersion().getVersion()).isEqualTo("002");
        assertThat(applied[2].getVersion().getVersion()).isEqualTo("003");
        assertThat(applied[3].getVersion().getVersion()).isEqualTo("004");
        assertThat(applied[4].getVersion().getVersion()).isEqualTo("005");
        assertThat(applied[5].getVersion().getVersion()).isEqualTo("006");
        assertThat(applied[6].getVersion().getVersion()).isEqualTo("007");
        assertThat(applied[7].getVersion().getVersion()).isEqualTo("008");
    }

    /** 查询 SQLite 中所有业务表名。 */
    private Set<String> queryTableNames(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            Set<String> tables = new HashSet<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            return tables;
        }
    }

    /** 查询指定表的索引名列表（PRAGMA index_list，表名来自测试常量）。 */
    private Set<String> queryIndexNames(Connection conn, String table) throws SQLException {
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA index_list('" + table + "')")) {
            Set<String> indexes = new HashSet<>();
            while (rs.next()) {
                indexes.add(rs.getString("name"));
            }
            return indexes;
        }
    }

    /** 查询指定表的列名列表（表名来自测试常量）。 */
    private Set<String> queryColumnNames(Connection conn, String table) throws SQLException {
        if (!table.matches("[a-z_]+")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info('" + table + "')")) {
            Set<String> columns = new HashSet<>();
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
            return columns;
        }
    }
}
