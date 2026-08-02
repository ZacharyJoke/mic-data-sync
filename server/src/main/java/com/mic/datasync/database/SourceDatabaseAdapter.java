package com.mic.datasync.database;

import com.mic.datasync.database.metadata.TableMetadata;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

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
}
