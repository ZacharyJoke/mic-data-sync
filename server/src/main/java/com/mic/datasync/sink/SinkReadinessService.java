package com.mic.datasync.sink;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.TargetDatabaseAdapter;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Sink 就绪状态。
 *
 * <p>READY 条件：目标数据源已配置、连接可用、回执表存在（不存在时先尝试自动创建）；
 * 无 DDL 权限创建失败时返回 NOT_READY 与 DBA 可执行的初始化 SQL。</p>
 */
@Service
public class SinkReadinessService {

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final DatabaseAdapterFactory adapterFactory;

    public SinkReadinessService(DatabaseConfigService configService,
                                ConnectionFactory connectionFactory,
                                DatabaseAdapterFactory adapterFactory) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.adapterFactory = adapterFactory;
    }

    /** 当前 Sink 就绪状态。 */
    public ReadinessResult readiness() {
        DatabaseConfig config = configService.getDefault(DatabaseRole.SINK).orElse(null);
        if (config == null) {
            return ReadinessResult.notReady("未配置 Sink 数据库，请先在数据库管理中配置", null);
        }
        return readinessFor(config);
    }

    /** 指定目标数据源档案的就绪状态。 */
    public ReadinessResult readinessFor(DatabaseConfig config) {
        if (config == null) {
            return ReadinessResult.notReady("未配置目标数据源", null);
        }
        try (Connection connection = connectionFactory.open(config)) {
            TargetDatabaseAdapter adapter = adapterFactory.targetAdapter(config.databaseType());
            if (adapter.receiptTableExists(connection)) {
                return ReadinessResult.readyResult();
            }
            // 回执表不存在：先尝试自动创建
            try (Statement statement = connection.createStatement()) {
                statement.execute(adapter.receiptInitializationDdl());
            } catch (Exception ex) {
                return ReadinessResult.notReady(
                        "回执表自动创建失败（目标账号可能缺少 DDL 权限），请由 DBA 执行初始化 SQL",
                        adapter.receiptInitializationDdl());
            }
            if (adapter.receiptTableExists(connection)) {
                return ReadinessResult.readyResult("回执表已自动创建");
            }
            return ReadinessResult.notReady("回执表初始化未生效", adapter.receiptInitializationDdl());
        } catch (Exception ex) {
            return ReadinessResult.notReady("Sink 数据库连接不可用", null);
        }
    }

    /** 就绪结果（capabilityStatus 为 READY/NOT_READY）。 */
    public record ReadinessResult(boolean ready, String status, String message, String dbaSql) {

        static ReadinessResult readyResult() {
            return new ReadinessResult(true, "READY", "Sink 就绪", null);
        }

        static ReadinessResult readyResult(String message) {
            return new ReadinessResult(true, "READY", message, null);
        }

        static ReadinessResult notReady(String message, String dbaSql) {
            return new ReadinessResult(false, "NOT_READY", message, dbaSql);
        }
    }
}
