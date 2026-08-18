package com.mic.datasync.database;

import com.mic.datasync.database.metadata.TableMetadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Source（Reader）数据库适配器：源侧能力探查。
 */
public interface SourceDatabaseAdapter {

    /** 适配的数据库类型。 */
    DatabaseType databaseType();

    /** 列出可用的 Schema。 */
    List<String> listSchemas(Connection connection) throws SQLException;

    /** 列出指定 Schema 下的业务表。 */
    List<String> listTables(Connection connection, String schema) throws SQLException;

    /** 读取表元数据（字段、主键、唯一索引、JDBC 类型）。 */
    TableMetadata readTableMetadata(Connection connection, String schema, String table) throws SQLException;

    /** 当前数据库时间（ISO-8601 字符串）。 */
    String currentDatabaseTime(Connection connection) throws SQLException;

    /** 测试查询：按给定字段最多读取 {@code maxRows} 行（MVP 上限 20）。 */
    List<List<Object>> sampleRows(Connection connection, String schema, String table,
                                  List<String> columns, int maxRows) throws SQLException;

    /** 能力探查：连接可用且能读取元数据时为 READY。 */
    CapabilityResult capability(Connection connection);

    /**
     * 检查给定列组合在当前表中「唯一且非 NULL」（业务唯一键实测）。
     *
     * <p>用于软唯一键：组合既非主键也非唯一索引时，启用前实测唯一性。
     * 任一组重复（GROUP BY ... HAVING COUNT(*) > 1）或任一键值含 NULL
     * （keyset 分页拒绝 NULL）都视为不可用，返回 false。</p>
     *
     * @return true 表示组合唯一且键值非 NULL，可安全作为 keyset 分页键
     */
    default boolean columnGroupIsUnique(Connection connection, String schema, String table,
                                        List<String> columns) throws SQLException {
        if (columns == null || columns.isEmpty()) {
            return true;
        }
        List<String> quoted = columns.stream()
                .map(SourceDatabaseAdapter::quoteIdentifier)
                .collect(Collectors.toCollection(ArrayList::new));
        String columnList = String.join(", ", quoted);
        String nullCondition = quoted.stream()
                .map(column -> column + " IS NULL")
                .collect(Collectors.joining(" OR "));
        String qualified = (schema == null || schema.isBlank())
                ? quoteIdentifier(table)
                : quoteIdentifier(schema) + "." + quoteIdentifier(table);
        // 重复组与含 NULL 行统一检测：任一命中即不可用
        String sql = """
                SELECT 1 FROM (
                    SELECT %s FROM %s GROUP BY %s HAVING COUNT(*) > 1
                    UNION ALL
                    SELECT %s FROM %s WHERE %s LIMIT 1
                ) mic_sync_dup LIMIT 1
                """.formatted(columnList, qualified, columnList, columnList, qualified, nullCondition);
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return !rs.next();
        }
    }

    /** 引用标识符（PG 系方言，与 SourceDialect 保持一致）。 */
    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
