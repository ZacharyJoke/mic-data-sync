package com.mic.datasync.database.support;

import com.mic.datasync.database.CapabilityResult;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.database.metadata.TableMetadata;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PostgreSQL 系（KingbaseES/openGauss）Target 适配器公共实现。
 */
public abstract class PostgresLikeTargetAdapter implements TargetDatabaseAdapter {

    /** 工具回执表名（与方案冻结一致）。 */
    public static final String RECEIPT_TABLE = "mic_sync_batch_receipt";

    @Override
    public List<String> listSchemas(Connection connection) throws SQLException {
        return PostgresMetadataReader.listSchemas(connection);
    }

    @Override
    public List<String> listTables(Connection connection, String schema) throws SQLException {
        return PostgresMetadataReader.listTables(connection, schema);
    }

    @Override
    public TableMetadata readTableMetadata(Connection connection, String schema, String table)
            throws SQLException {
        return PostgresMetadataReader.readTableMetadata(connection, schema, table);
    }

    @Override
    public boolean hasUniqueConstraint(TableMetadata metadata, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        Set<String> normalized = new HashSet<>();
        columns.forEach(c -> normalized.add(c.toLowerCase()));
        // 主键完全匹配
        if (matches(metadata.primaryKeyColumns(), normalized)) {
            return true;
        }
        // 任一唯一索引完全匹配
        for (List<String> uniqueIndex : metadata.uniqueIndexes()) {
            if (matches(uniqueIndex, normalized)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean receiptTableExists(Connection connection) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables "
                + "WHERE table_name = '" + RECEIPT_TABLE + "' "
                + "AND table_schema = current_schema() LIMIT 1";
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next();
        }
    }

    @Override
    public String receiptInitializationDdl() {
        // 回执唯一约束为 (source_instance_id, batch_id)，保证跨 Source 实例的批次可复用
        return "CREATE TABLE IF NOT EXISTS " + RECEIPT_TABLE + " ("
                + "  batch_id VARCHAR(64) NOT NULL,"
                + "  source_instance_id VARCHAR(64) NOT NULL,"
                + "  task_id VARCHAR(64) NOT NULL,"
                + "  run_id VARCHAR(64) NOT NULL,"
                + "  batch_sequence BIGINT NOT NULL,"
                + "  payload_hash VARCHAR(128) NOT NULL,"
                + "  received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "  PRIMARY KEY (source_instance_id, batch_id)"
                + ")";
    }

    @Override
    public CapabilityResult capability(Connection connection) {
        try {
            receiptTableExists(connection);
            return CapabilityResult.ready();
        } catch (SQLException ex) {
            return CapabilityResult.blocked("DATABASE_CONNECTION_FAILED",
                    "Target 能力检查失败", List.of("检查连接串、账号权限与数据库服务状态"));
        }
    }

    private boolean matches(List<String> candidates, Set<String> normalized) {
        if (candidates == null || candidates.isEmpty() || candidates.size() != normalized.size()) {
            return false;
        }
        Set<String> candidateSet = new HashSet<>();
        candidates.forEach(c -> candidateSet.add(c.toLowerCase()));
        return candidateSet.equals(normalized);
    }
}
