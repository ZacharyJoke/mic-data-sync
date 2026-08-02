package com.mic.datasync.sink;

import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.database.metadata.TableMetadata;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
     * @param uniqueKeys 为空时退化为普通 INSERT
     */
    public void upsert(Connection connection, DatabaseType type, String schema, String table,
                       List<String> columns, List<String> uniqueKeys, List<List<Object>> rows,
                       TableMetadata metadata)
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
        String sql = adapter.buildUpsertSql(schema, table, columns, uniqueKeys, nonUpdatableColumns);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (List<Object> row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    statement.setObject(i + 1, row.get(i));
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
