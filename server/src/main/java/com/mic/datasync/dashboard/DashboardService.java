package com.mic.datasync.dashboard;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.ConnectionFactory.ConnectionTestResult;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.instance.InstanceService;
import com.mic.datasync.sink.SinkReadinessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 工作台聚合：连接状态、实例、任务与运行指标、待处理异常。
 */
@Service
public class DashboardService {

    private static final String RANKED_RUN_CTE = """
            WITH ranked AS (
                SELECT run_id, task_id, task_name_snapshot, status, started_at, pause_reason,
                       ROW_NUMBER() OVER (
                           PARTITION BY task_id
                           ORDER BY julianday(started_at) DESC, run_id DESC
                       ) AS rn
                FROM run
            )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final SinkReadinessService sinkReadinessService;
    private final InstanceService instanceService;
    private final ZoneId zoneId;

    public DashboardService(
            JdbcTemplate jdbcTemplate,
            DatabaseConfigService configService,
            ConnectionFactory connectionFactory,
            SinkReadinessService sinkReadinessService,
            InstanceService instanceService,
            @Value("${mic.sync.timezone:Asia/Shanghai}") String timezone) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.sinkReadinessService = sinkReadinessService;
        this.instanceService = instanceService;
        this.zoneId = ZoneId.of(timezone);
    }

    public DashboardSummary summary(Instant now) {
        Instant statisticsFrom = now.atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant();
        Instant statisticsTo = statisticsFrom.plusSeconds(86400);

        long enabledTaskCount = count("""
                SELECT COUNT(*) FROM task WHERE lifecycle_status = 'ENABLED'
                """);
        long activeRunCount = count("""
                SELECT COUNT(*) FROM run
                WHERE status IN ('RUNNING', 'WAITING_RETRY', 'UNKNOWN', 'PAUSED')
                """);
        BigDecimal todaySuccessRate = todaySuccessRate(statisticsFrom, statisticsTo);
        List<FailureAlert> alerts = failureAlerts();
        return new DashboardSummary(
                sourceSummary(),
                sinkSummary(),
                new InstanceSummary(
                        instanceService.instanceId().toString(),
                        instanceService.applicationVersion(),
                        instanceService.roles(),
                        "READY"),
                enabledTaskCount,
                activeRunCount,
                todaySuccessRate,
                alerts.size(),
                statisticsFrom,
                statisticsTo,
                recentRuns(),
                alerts);
    }

    private ConnectionSummary sourceSummary() {
        Optional<DatabaseConfig> config = configService.getDefault(DatabaseRole.SOURCE);
        if (config.isEmpty()) {
            return new ConnectionSummary(false, null, false, "未配置 Source 数据库");
        }
        ConnectionTestResult test = connectionFactory.testConnection(config.get());
        return new ConnectionSummary(
                true,
                config.get().databaseType().name(),
                test.ok(),
                test.ok() ? "连接正常" : (test.message() == null ? "连接失败" : test.message()));
    }

    private ConnectionSummary sinkSummary() {
        Optional<DatabaseConfig> config = configService.getDefault(DatabaseRole.SINK);
        if (config.isEmpty()) {
            return new ConnectionSummary(false, null, false, "未配置 Sink 数据库");
        }
        SinkReadinessService.ReadinessResult readiness = sinkReadinessService.readiness();
        return new ConnectionSummary(
                true,
                config.get().databaseType().name(),
                readiness.ready(),
                readiness.message());
    }

    private BigDecimal todaySuccessRate(Instant from, Instant to) {
        ResultSetRow row = jdbcTemplate.queryForObject("""
                SELECT
                    SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,
                    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed
                FROM run
                WHERE julianday(started_at) >= julianday(?)
                  AND julianday(started_at) < julianday(?)
                """, (rs, rowNum) -> new ResultSetRow(
                        rs.getLong("succeeded"), rs.getLong("failed")),
                from.toString(), to.toString());
        long total = row.succeeded() + row.failed();
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(row.succeeded())
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }

    private List<RecentRun> recentRuns() {
        return jdbcTemplate.query("""
                SELECT run_id, task_id, task_name_snapshot, kind, status,
                       started_at, ended_at, source_row_count, confirmed_row_count
                FROM run
                ORDER BY julianday(started_at) DESC, run_id DESC
                LIMIT 10
                """, (rs, rowNum) -> new RecentRun(
                        rs.getString("run_id"),
                        rs.getString("task_id"),
                        rs.getString("task_name_snapshot"),
                        rs.getString("kind"),
                        rs.getString("status"),
                        rs.getString("started_at"),
                        rs.getString("ended_at"),
                        rs.getLong("source_row_count"),
                        rs.getLong("confirmed_row_count")));
    }

    private List<FailureAlert> failureAlerts() {
        return jdbcTemplate.query(RANKED_RUN_CTE + """
                SELECT r.run_id, r.task_id, r.task_name_snapshot, r.started_at, r.pause_reason,
                       f.stage AS failure_stage, f.summary AS failure_summary,
                       f.occurred_at AS failure_occurred_at
                FROM ranked r
                LEFT JOIN run_failure f ON f.run_id = r.run_id
                WHERE r.rn = 1 AND r.status = 'FAILED'
                ORDER BY julianday(r.started_at) DESC
                """, (rs, rowNum) -> {
            String pauseReason = rs.getString("pause_reason");
            String failureSummary = rs.getString("failure_summary");
            String failureStage = rs.getString("failure_stage");
            String failureOccurredAt = rs.getString("failure_occurred_at");
            return new FailureAlert(
                    rs.getString("run_id"),
                    rs.getString("task_id"),
                    rs.getString("task_name_snapshot"),
                    failureStage == null ? "UNKNOWN" : failureStage,
                    failureSummary != null
                            ? failureSummary
                            : (pauseReason == null || pauseReason.isBlank() ? "运行失败" : pauseReason),
                    failureOccurredAt == null ? rs.getString("started_at") : failureOccurredAt,
                    "ERROR");
        });
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private record ResultSetRow(long succeeded, long failed) {
    }

    public record DashboardSummary(
            ConnectionSummary source,
            ConnectionSummary sink,
            InstanceSummary instance,
            long enabledTaskCount,
            long activeRunCount,
            BigDecimal todaySuccessRate,
            long unresolvedFailureCount,
            Instant statisticsFrom,
            Instant statisticsTo,
            List<RecentRun> recentRuns,
            List<FailureAlert> alerts) {
    }

    public record ConnectionSummary(
            boolean configured,
            String product,
            boolean healthy,
            String message) {
    }

    public record InstanceSummary(
            String instanceId,
            String version,
            String roles,
            String readiness) {
    }

    public record RecentRun(
            String runId,
            String taskId,
            String taskName,
            String kind,
            String status,
            String startedAt,
            String endedAt,
            long sourceRowCount,
            long confirmedRowCount) {
    }

    public record FailureAlert(
            String runId,
            String taskId,
            String taskName,
            String stage,
            String summary,
            String occurredAt,
            String severity) {
    }
}
