package com.mic.datasync.run;

import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.task.TaskService.TaskRecord;
import com.mic.datasync.task.domain.TaskDefinition.LifecycleStatus;
import com.mic.datasync.task.domain.TaskDefinition.WriteMode;
import com.mic.datasync.transport.SinkResponseClassifier.Outcome;
import com.mic.datasync.transport.SinkTransport;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 手动增量续采门控集成测试（需要真实 KingbaseES/openGauss 源库）。
 *
 * <p>启用方式：{@code -Dcontract.db.url=jdbc:... -Dcontract.db.username=... -Dcontract.db.password=...}
 * （可选 {@code -Dcontract.db.type=KINGBASE_ES|OPEN_GAUSS}，默认 OPEN_GAUSS）。
 * Sink 传输以 Mock 返回 CONFIRMED，本测试聚焦源端读取与 checkpoint 语义。</p>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "contract.db.url", matches = ".+")
class IncrementalCursorIntegrationTest {

    private static final Identifiers.TaskId CONTRACT_TASK_ID =
            Identifiers.TaskId.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired
    private RunEngine engine;

    @Autowired
    private DatabaseConfigService configService;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private SinkTransport transport;

    @Test
    void incrementalAfterFullPicksUpNewRowsAndReReadsWindow() throws Exception {
        String url = System.getProperty("contract.db.url");
        String user = System.getProperty("contract.db.username", "");
        String password = System.getProperty("contract.db.password", "");
        boolean kingbase = "KINGBASE_ES".equalsIgnoreCase(
                System.getProperty("contract.db.type", "OPEN_GAUSS"));
        DatabaseType type = kingbase ? DatabaseType.KINGBASE_ES : DatabaseType.OPEN_GAUSS;
        String table = "mic_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        List<SinkTransport.SendRequest> sends = new ArrayList<>();
        when(transport.send(any())).thenAnswer(invocation -> {
            sends.add(invocation.getArgument(0));
            return new SinkTransport.SendResult(Outcome.CONFIRMED, 200, null, null, 1);
        });

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + table
                        + " (id BIGINT PRIMARY KEY, updated_at TIMESTAMP)");
            }
            insertRow(connection, table, 1, "2026-08-01T10:00:00Z");

            DatabaseConfig source = configService.create("self-source", null,
                    "contract-" + table, type, url, user, password, "jdbc");
            TaskRecord task = taskFor(table, source.id());
            insertTaskRow(task, table);
            try {
                // 首次全量：读到 A
                engine.execute(task, RunService.RunKind.INITIAL_FULL);
                assertThat(flatIds(sends)).containsExactly(1L);
                sends.clear();

                // 全量后新增 B/C
                insertRow(connection, table, 2, "2026-08-01T10:06:00Z");
                insertRow(connection, table, 3, "2026-08-01T10:07:00Z");

                // 手动增量：下界 = A 的时间 − 10 分钟；不设上界（时间字段可能为未来时间）
                // → 重读 A 并续采 B/C
                engine.execute(task, RunService.RunKind.INCREMENTAL);
                assertThat(flatIds(sends)).containsExactlyInAnyOrder(1L, 2L, 3L);

                // checkpoint 游标持久化了已确认时间
                Identifiers.TaskId taskId = task.taskId();
                assertThat(checkpointRepository.get(taskId).orElseThrow().cursorValues())
                        .containsKey("updated_at");
            } finally {
                jdbcTemplate.update("DELETE FROM data_source WHERE id = ?", source.id());
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE " + table);
                }
            }
        }
    }

    private TaskRecord taskFor(String table, String sourceId) {
        return new TaskRecord(
                CONTRACT_TASK_ID, "contract-task", 1, LifecycleStatus.ENABLED, "TABLE",
                new TableReadDefinition("public", table, List.of("id", "updated_at"), List.of(),
                        List.of("id"), "updated_at"),
                "public", table + "_copy", WriteMode.UPSERT,
                List.of("id"), List.of(), "http://sink:19090", null,
                Identifiers.InstanceId.generate(),
                "self-source", "self-sink", null, "sink-default",
                Instant.now(), Instant.now());
    }

    /** 插入对应 task 行，保证 checkpoint 外键可写（SQLite 可能启用外键约束）。 */
    private void insertTaskRow(TaskRecord task, String table) {
        String now = Instant.now().toString();
        String readDefinitionJson = "{\"schema\":\"public\",\"table\":\"" + table
                + "\",\"selectedColumns\":[\"id\",\"updated_at\"],\"filters\":[],"
                + "\"paginationKeys\":[\"id\"],\"updatedTimeField\":\"updated_at\"}";
        jdbcTemplate.update("""
                INSERT INTO task (
                    task_id, name, version, lifecycle_status, read_mode, read_definition,
                    target_schema, target_table, write_mode, unique_keys, field_mappings,
                    remote_sink_url, sink_token_ref, expected_sink_instance_id, created_at, updated_at)
                VALUES (?, 'contract-task', 1, 'ENABLED', 'TABLE', ?, 'public', ?, 'UPSERT', '["id"]',
                    '[{"sourceField":"id","targetField":"id"}]', 'http://sink:19090', 'sink-token', NULL, ?, ?)
                """, task.taskId().toString(), readDefinitionJson, table + "_copy", now, now);
    }

    private static void insertRow(Connection connection, String table, long id, String updatedAt)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (id, updated_at) VALUES (?, CAST(? AS TIMESTAMP))")) {
            statement.setLong(1, id);
            statement.setString(2, updatedAt);
            statement.executeUpdate();
        }
    }

    private static List<Object> flatIds(List<SinkTransport.SendRequest> sends) {
        List<Object> ids = new ArrayList<>();
        for (SinkTransport.SendRequest send : sends) {
            BatchPayload payload = send.payload();
            for (List<Object> row : payload.rows()) {
                ids.add(row.get(0));
            }
        }
        return ids;
    }
}
