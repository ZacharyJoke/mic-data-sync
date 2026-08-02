package com.mic.datasync.database.opengauss;

import com.mic.datasync.database.CapabilityResult;
import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.SourceDatabaseAdapter;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.database.support.PostgresMetadataReader;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * openGauss Source（Reader）适配器。
 */
public class OpenGaussSourceAdapter implements SourceDatabaseAdapter {

    @Override
    public DatabaseType databaseType() {
        return DatabaseType.OPEN_GAUSS;
    }

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
    public String currentDatabaseTime(Connection connection) throws SQLException {
        return PostgresMetadataReader.currentDatabaseTime(connection);
    }

    @Override
    public List<List<Object>> sampleRows(Connection connection, String schema, String table,
                                         List<String> columns, int maxRows) throws SQLException {
        return PostgresMetadataReader.sampleRows(connection, schema, table, columns, maxRows);
    }

    @Override
    public CapabilityResult capability(Connection connection) {
        try {
            listSchemas(connection);
            return CapabilityResult.ready();
        } catch (SQLException ex) {
            return CapabilityResult.blocked("DATABASE_CONNECTION_FAILED",
                    "openGauss Source 元数据读取失败", List.of("检查连接串、账号 SELECT 权限与数据库服务状态"));
        }
    }
}
