package com.mic.datasync.sink;

import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 目标表批量写入：按方言生成 UPSERT SQL 并批量执行。
 */
@Component
public class TargetBatchWriter {

    private final DatabaseAdapterFactory adapterFactory;

    public TargetBatchWriter(DatabaseAdapterFactory adapterFactory) {
        this.adapterFactory = adapterFactory;
    }

    /**
     * 批量 UPSERT 到目标表（使用调用方连接，事务由调用方控制）。
     *
     * @param uniqueKeys      为空时退化为普通 INSERT
     * @param skipOnConflict  冲突时保留目标行并跳过（DO NOTHING / 无操作更新）
     * @param postgresProtocol 连接使用 jdbc:postgresql 协议（PostgreSQL 兼容模式，如 Vastbase）
     */
    public void upsert(Connection connection, DatabaseType type, String schema, String table,
                       List<String> columns, List<String> uniqueKeys, List<List<Object>> rows,
                       TableMetadata metadata, boolean skipOnConflict, boolean postgresProtocol)
            throws SQLException {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        TargetDatabaseAdapter adapter = adapterFactory.targetAdapter(type);
        Set<String> nonUpdatableColumns = new HashSet<>();
        if (metadata != null) {
            metadata.primaryKeyColumns().forEach(column ->
                    nonUpdatableColumns.add(column.toLowerCase(Locale.ROOT)));
            metadata.uniqueIndexes().forEach(index -> index.forEach(column ->
                    nonUpdatableColumns.add(column.toLowerCase(Locale.ROOT))));
        }
        // openGauss（jdbc:opengauss）冲突跳过需要非键列做无操作更新；PostgreSQL 系 DO NOTHING 不需要
        String noOpColumn = skipOnConflict && !postgresProtocol
                ? pickNoOpColumn(metadata, nonUpdatableColumns) : null;
        String sql = adapter.buildUpsertSql(
                schema, table, columns, uniqueKeys, nonUpdatableColumns,
                skipOnConflict, noOpColumn, postgresProtocol);
        Map<String, ColumnMetadata> columnMap = metadata == null ? Map.of() : metadata.columnMap();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (List<Object> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    statement.setObject(i + 1, convertToTargetColumnValue(
                            row.get(i), columnMap.get(columns.get(i).toLowerCase(Locale.ROOT))));
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** 选择目标表中第一个非主键/非唯一索引列作为 openGauss 无操作更新列。 */
    private static String pickNoOpColumn(TableMetadata metadata, Set<String> nonUpdatableColumns) {
        if (metadata != null) {
            for (ColumnMetadata column : metadata.columns()) {
                String normalized = column.name().toLowerCase(Locale.ROOT);
                if (!nonUpdatableColumns.contains(normalized)) {
                    return column.name();
                }
            }
        }
        return null;
    }

    /**
     * 目标列类型与源值类型不一致时做兼容转换。
     *
     * <p>跨库同步常见源 smallint（0/1）→ 目标 boolean 的场景：直接把整数写入
     * boolean 列会被 Vastbase/openGauss 拒绝（如 {@code is_active}）。转换只针对
     * 明确的目标类型差异，其余值原样交给 JDBC 驱动处理。</p>
     */
    private static Object convertToTargetColumnValue(Object value, ColumnMetadata targetColumn) {
        if (value == null || targetColumn == null) {
            return value;
        }
        int jdbcType = targetColumn.jdbcType();
        if (jdbcType == Types.BOOLEAN || jdbcType == Types.BIT) {
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value instanceof Number number) {
                return number.longValue() != 0L;
            }
            String text = value.toString().trim();
            if ("1".equals(text) || "true".equalsIgnoreCase(text)) {
                return Boolean.TRUE;
            }
            if ("0".equals(text) || "false".equalsIgnoreCase(text)) {
                return Boolean.FALSE;
            }
        }
        return value;
    }
}
