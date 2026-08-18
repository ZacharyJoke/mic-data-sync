package com.mic.datasync.database;

import com.mic.datasync.database.dialect.WriterDialect;
import com.mic.datasync.database.metadata.TableMetadata;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Target（Writer）数据库适配器：目标侧写入能力探查。
 */
public interface TargetDatabaseAdapter extends WriterDialect {

    /** 适配的数据库类型。 */
    DatabaseType databaseType();

    /** 列出非系统 Schema。 */
    List<String> listSchemas(Connection connection) throws SQLException;

    /** 列出指定 Schema 下的业务表。 */
    List<String> listTables(Connection connection, String schema) throws SQLException;

    /** 读取目标表元数据。 */
    TableMetadata readTableMetadata(Connection connection, String schema, String table) throws SQLException;

    /**
     * 检查给定字段是否构成稳定的唯一约束（主键或唯一索引完全匹配）。
     */
    boolean hasUniqueConstraint(TableMetadata metadata, List<String> columns);

    /** 回执表是否已存在。 */
    boolean receiptTableExists(Connection connection) throws SQLException;

    /** 回执表初始化 DDL（允许 DBA 手动执行）。 */
    String receiptInitializationDdl();

    /** 能力探查：连接可用且回执表检查可执行为 READY。 */
    CapabilityResult capability(Connection connection);
}
