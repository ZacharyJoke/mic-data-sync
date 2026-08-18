package com.mic.datasync.database;

import com.mic.datasync.instance.RoleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * 数据库连接与能力探查。
 *
 * <p>连接测试返回连通性、产品名/版本、SELECT 能力、事务能力、当前用户等，
 * 不返回密码；失败时返回稳定错误码与用户可执行建议（不泄露密码与完整连接串）。</p>
 */
@Component
public class ConnectionFactory {

    private static final Logger log = LoggerFactory.getLogger(ConnectionFactory.class);

    private final RoleProperties roleProperties;

    public ConnectionFactory(RoleProperties roleProperties) {
        this.roleProperties = roleProperties;
    }

    /** 驱动目录：默认 {@code ${dataDir}/drivers}，可用 {@code mic.sync.driver-dir} 覆盖。 */
    public Path driverDirectory() {
        return Path.of(roleProperties.driverDir());
    }

    /** 使用给定配置建立连接（驱动加载失败或连接失败抛出异常）。 */
    public Connection open(DatabaseConfig config) throws SQLException {
        Driver driver = DriverLoader.load(config.databaseType(), driverDirectory(), config.jdbcUrl());
        Properties props = new Properties();
        props.setProperty("user", config.username());
        props.setProperty("password", config.password());
        Connection connection = driver.connect(config.jdbcUrl(), props);
        if (connection == null) {
            throw new SQLException("驱动无法处理该 JDBC URL（请检查连接串格式）");
        }
        return connection;
    }

    /** 测试连接并探查能力。 */
    public ConnectionTestResult testConnection(DatabaseConfig config) {
        try (Connection connection = open(config)) {
            DatabaseMetaData meta = connection.getMetaData();
            String productName = safe(meta::getDatabaseProductName);
            String productVersion = safe(meta::getDatabaseProductVersion);
            String currentUser = queryCurrentUser(connection);
            boolean selectCapable = testSelect(connection);
            boolean transactionCapable = testTransaction(connection);
            return ConnectionTestResult.ok(
                    productName, productVersion, selectCapable, transactionCapable, currentUser);
        } catch (DriverLoader.DriverLoadException ex) {
            log.warn("数据库连接测试失败（驱动加载）: {}", ex.getMessage());
            return ConnectionTestResult.failed("DATABASE_CAPABILITY_BLOCKED", ex.getMessage());
        } catch (SQLException ex) {
            log.warn("数据库连接测试失败（连接/探查）: {}", ex.getMessage());
            return ConnectionTestResult.failed("DATABASE_CONNECTION_FAILED",
                    "无法连接数据库，请检查地址、端口、凭据与服务状态");
        } catch (RuntimeException ex) {
            log.warn("数据库连接测试失败（未知）: {}", ex.getMessage());
            return ConnectionTestResult.failed("DATABASE_CONNECTION_FAILED", "连接测试发生未知错误");
        }
    }

    private boolean testSelect(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (SQLException ex) {
            return false;
        }
    }

    private boolean testTransaction(Connection connection) {
        boolean originalAutoCommit;
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            connection.rollback();
            return true;
        } catch (SQLException ex) {
            return false;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // 忽略还原失败
            }
        }
    }

    private String queryCurrentUser(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT CURRENT_USER")) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException ex) {
            return null;
        }
    }

    private static String safe(SqlSupplier<String> supplier) {
        try {
            return supplier.get();
        } catch (SQLException ex) {
            return null;
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    /** 连接测试结果（不包含密码）。 */
    public record ConnectionTestResult(
            boolean ok,
            String productName,
            String productVersion,
            Boolean selectCapable,
            Boolean transactionCapable,
            String currentUser,
            String errorCode,
            String message) {

        static ConnectionTestResult ok(String productName, String productVersion,
                                       boolean selectCapable, boolean transactionCapable,
                                       String currentUser) {
            return new ConnectionTestResult(true, productName, productVersion,
                    selectCapable, transactionCapable, currentUser, null, null);
        }

        static ConnectionTestResult failed(String errorCode, String message) {
            return new ConnectionTestResult(false, null, null, null, null, null, errorCode, message);
        }
    }
}
