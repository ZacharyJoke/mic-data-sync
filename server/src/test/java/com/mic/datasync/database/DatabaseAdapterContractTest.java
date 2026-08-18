package com.mic.datasync.database;

import com.mic.datasync.database.dialect.WriterDialect;
import com.mic.datasync.database.kingbase.KingbaseSourceAdapter;
import com.mic.datasync.database.kingbase.KingbaseTargetAdapter;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.database.opengauss.OpenGaussSourceAdapter;
import com.mic.datasync.database.opengauss.OpenGaussTargetAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 四个 Reader/Writer Adapter 的最小契约测试。
 *
 * <p>方言、方向、唯一约束等纯逻辑部分在本测试始终运行；
 * 真实 KingbaseES/openGauss 契约测试由三方向 E2E（Task 19，配置
 * {@code contract.*} 系统属性或环境）执行，本地无真实数据库时跳过。</p>
 */
class DatabaseAdapterContractTest {

    private final WriterDialect dialect = new WriterDialect() {
    };

    @Test
    void bothSourceAdaptersExposeExpectedDatabaseType() {
        assertThat(new KingbaseSourceAdapter().databaseType()).isEqualTo(DatabaseType.KINGBASE_ES);
        assertThat(new OpenGaussSourceAdapter().databaseType()).isEqualTo(DatabaseType.OPEN_GAUSS);
    }

    @Test
    void bothTargetAdaptersExposeExpectedDatabaseType() {
        assertThat(new KingbaseTargetAdapter().databaseType()).isEqualTo(DatabaseType.KINGBASE_ES);
        assertThat(new OpenGaussTargetAdapter().databaseType()).isEqualTo(DatabaseType.OPEN_GAUSS);
    }

    @Test
    void supportedDirectionsAreAccepted() {
        assertThat(DatabaseDirection.isSupported(DatabaseType.KINGBASE_ES, DatabaseType.OPEN_GAUSS)).isTrue();
        assertThat(DatabaseDirection.isSupported(DatabaseType.OPEN_GAUSS, DatabaseType.OPEN_GAUSS)).isTrue();
        assertThat(DatabaseDirection.isSupported(DatabaseType.OPEN_GAUSS, DatabaseType.KINGBASE_ES)).isTrue();
    }

    @Test
    void kingbaseToKingbaseDirectionIsUnsupported() {
        assertThat(DatabaseDirection.isSupported(DatabaseType.KINGBASE_ES, DatabaseType.KINGBASE_ES)).isFalse();
    }

    @Test
    void upsertSqlGeneratedWithConflictAndUpdate() {
        String sql = dialect.buildUpsertSql("public", "patient", List.of("id", "name", "status"), List.of("id"));
        assertThat(sql).startsWith("INSERT INTO \"public\".\"patient\" (\"id\", \"name\", \"status\") VALUES (?, ?, ?)");
        assertThat(sql).contains("ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\", \"status\" = EXCLUDED.\"status\"");
    }

    @Test
    void upsertSqlWithoutUniqueKeyDegradesToPlainInsert() {
        String sql = dialect.buildUpsertSql(null, "audit_log", List.of("id", "message"), List.of());
        assertThat(sql).isEqualTo("INSERT INTO \"audit_log\" (\"id\", \"message\") VALUES (?, ?)");
        assertThat(sql).doesNotContain("ON CONFLICT");
    }

    @Test
    void skipConflictGeneratesOnConflictDoNothing() {
        String sql = dialect.buildUpsertSql(
                "public", "patient", List.of("id", "name", "status"), List.of("id"),
                Set.of(), true, null);
        assertThat(sql).startsWith("INSERT INTO \"public\".\"patient\" (\"id\", \"name\", \"status\") VALUES (?, ?, ?)");
        assertThat(sql).contains("ON CONFLICT DO NOTHING");
        assertThat(sql).doesNotContain("ON CONFLICT (");
        assertThat(sql).doesNotContain("DO UPDATE");
    }

    @Test
    void postgresProtocolSkipConflictGeneratesOnConflictDoNothingForOpenGaussAdapter() {
        String sql = new OpenGaussTargetAdapter().buildUpsertSql(
                "public", "patient", List.of("id", "name", "status"), List.of("id"),
                Set.of(), true, null, true);
        assertThat(sql).contains("ON CONFLICT DO NOTHING");
        assertThat(sql).doesNotContain("ON CONFLICT (");
        assertThat(sql).doesNotContain("ON DUPLICATE");
    }

    /**
     * 回归：目标表除配置的唯一 Key 外还有业务唯一索引（如
     * {@code study_info_hospital_id_idx (hospital_id, study_pk)}）时，冲突跳过
     * SQL 不得锁定单一仲裁键，否则撞到其他唯一索引会整批报错回滚。
     */
    @Test
    void skipConflictOmitsArbiterWhenTargetHasAdditionalUniqueIndexes() {
        String sql = new OpenGaussTargetAdapter().buildUpsertSql(
                "guangdong", "study_info",
                List.of("check_serial_num", "hospital_id", "study_pk"),
                List.of("check_serial_num"),
                Set.of(), true, null, true);
        assertThat(sql).contains("ON CONFLICT DO NOTHING");
        assertThat(sql).doesNotContain("ON CONFLICT (\"check_serial_num\")");
        assertThat(sql).doesNotContain("ON DUPLICATE");
    }

    @Test
    void postgresProtocolNonSkipGeneratesPostgresStyleDoUpdate() {
        String sql = new OpenGaussTargetAdapter().buildUpsertSql(
                "public", "patient", List.of("id", "name", "status"), List.of("id"),
                Set.of(), false, null, true);
        assertThat(sql).contains("ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\", \"status\" = EXCLUDED.\"status\"");
        assertThat(sql).doesNotContain("ON DUPLICATE");
    }

    @Test
    void openGaussUpsertUsesOnDuplicateKeyUpdate() {
        String sql = new OpenGaussTargetAdapter().buildUpsertSql(
                "mic_sync", "patient", List.of("id", "name", "status"), List.of("id"));
        assertThat(sql).startsWith("INSERT INTO \"mic_sync\".\"patient\" (\"id\", \"name\", \"status\") VALUES (?, ?, ?)");
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE \"name\" = EXCLUDED.\"name\", \"status\" = EXCLUDED.\"status\"");
        assertThat(sql).doesNotContain("ON CONFLICT");
    }

    @Test
    void openGaussUpsertExcludesAllPrimaryAndUniqueColumnsFromUpdate() {
        String sql = new OpenGaussTargetAdapter().buildUpsertSql(
                "public", "sales_order_sink",
                List.of("id", "order_no", "amount", "note"),
                List.of("id"),
                Set.of("id", "order_no"));
        assertThat(sql).startsWith("INSERT INTO \"public\".\"sales_order_sink\" (\"id\", \"order_no\", \"amount\", \"note\") VALUES (?, ?, ?, ?)");
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE \"amount\" = EXCLUDED.\"amount\", \"note\" = EXCLUDED.\"note\"");
        assertThat(sql).doesNotContain("EXCLUDED.\"id\"");
        assertThat(sql).doesNotContain("EXCLUDED.\"order_no\"");
    }

    @Test
    void openGaussUpsertWithoutUniqueKeyIsPlainInsert() {
        String sql = new OpenGaussTargetAdapter().buildUpsertSql(
                null, "audit_log", List.of("id", "message"), List.of());
        assertThat(sql).isEqualTo("INSERT INTO \"audit_log\" (\"id\", \"message\") VALUES (?, ?)");
        assertThat(sql).doesNotContain("ON DUPLICATE");
    }

    @Test
    void openGaussSkipConflictUsesNoOpUpdateOnNonKeyColumn() {
        String sql = new OpenGaussTargetAdapter().buildUpsertSql(
                "mic_sync", "patient", List.of("id", "name", "status"), List.of("id"),
                Set.of("id"), true, "status", false);
        assertThat(sql).startsWith("INSERT INTO \"mic_sync\".\"patient\" (\"id\", \"name\", \"status\") VALUES (?, ?, ?)");
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE \"status\" = \"status\"");
        assertThat(sql).doesNotContain("EXCLUDED");
        assertThat(sql).doesNotContain("ON CONFLICT");
    }

    @Test
    void openGaussSkipConflictWithoutNoOpColumnRejects() {
        assertThatThrownBy(() -> new OpenGaussTargetAdapter().buildUpsertSql(
                "mic_sync", "patient", List.of("id", "name"), List.of("id"),
                Set.of("id"), true, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无操作更新列");
    }

    @Test
    void quoteIdentifierEscapesEmbeddedQuotes() {
        assertThat(dialect.quoteIdentifier("we\"ird")).isEqualTo("\"we\"\"ird\"");
    }

    @Test
    void hasUniqueConstraintMatchesPrimaryKey() {
        TableMetadata metadata = new TableMetadata(
                "public", "patient",
                List.of(
                        new ColumnMetadata("id", java.sql.Types.BIGINT, "bigint", 0, false, true),
                        new ColumnMetadata("name", java.sql.Types.VARCHAR, "varchar", 64, true, false)),
                List.of("id"),
                List.of());

        KingbaseTargetAdapter adapter = new KingbaseTargetAdapter();
        assertThat(adapter.hasUniqueConstraint(metadata, List.of("id"))).isTrue();
        assertThat(adapter.hasUniqueConstraint(metadata, List.of("name"))).isFalse();
    }

    @Test
    void hasUniqueConstraintMatchesUniqueIndexCaseInsensitively() {
        TableMetadata metadata = new TableMetadata(
                "public", "patient",
                List.of(new ColumnMetadata("card_no", java.sql.Types.VARCHAR, "varchar", 32, true, false)),
                List.of(),
                List.of(List.of("card_no")));

        OpenGaussTargetAdapter adapter = new OpenGaussTargetAdapter();
        assertThat(adapter.hasUniqueConstraint(metadata, List.of("CARD_NO"))).isTrue();
    }

    @Test
    void receiptDdlContainsRequiredColumns() {
        String ddl = new KingbaseTargetAdapter().receiptInitializationDdl();
        assertThat(ddl).contains("mic_sync_batch_receipt")
                .contains("batch_id")
                .contains("source_instance_id")
                .contains("payload_hash")
                .contains("received_at");
    }
}
