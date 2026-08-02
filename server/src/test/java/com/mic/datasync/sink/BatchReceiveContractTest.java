package com.mic.datasync.sink;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.instance.InstanceService;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.sink.ReceiptRepository.BatchReceipt;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次接收编排契约测试：幂等、Hash 冲突、同事务与回滚。
 *
 * <p>真实 KingbaseES/openGauss 事务与幂等测试由三方向 E2E（Task 19，
 * 配置 {@code contract.db.url}）执行；本地使用 Mockito 验证服务编排。</p>
 */
@ExtendWith(MockitoExtension.class)
class BatchReceiveContractTest {

    @Mock
    private Connection connection;
    @Mock
    private TargetDatabaseAdapter adapter;
    @Mock
    private ReceiptRepository receiptRepository;
    @Mock
    private TargetBatchWriter batchWriter;
    @Mock
    private DatabaseConfigService configService;
    @Mock
    private ConnectionFactory connectionFactory;
    @Mock
    private DatabaseAdapterFactory adapterFactory;
    @Mock
    private SinkReadinessService readinessService;
    @Mock
    private InstanceService instanceService;

    private BatchReceiveService service;

    private static final Identifiers.InstanceId SOURCE = Identifiers.InstanceId.generate();
    private static final Identifiers.InstanceId SINK = Identifiers.InstanceId.generate();

    @BeforeEach
    void setUp() {
        service = new BatchReceiveService(configService, connectionFactory, adapterFactory,
                receiptRepository, batchWriter, readinessService, instanceService);
    }

    private BatchPayload payload(String batchId) {
        return new BatchPayload(
                1,
                SOURCE,
                SINK,
                null,
                Identifiers.TaskId.generate(),
                Identifiers.RunId.generate(),
                Identifiers.BatchId.fromString(batchId),
                1L,
                new BatchPayload.TargetTable("public", "patient"),
                List.of("id", "name"),
                List.of(List.of(1L, "张三")),
                new BatchPayload.CheckpointCandidate(Map.of("id", 1L)));
    }

    private TableMetadata patientMetadata() {
        return new TableMetadata(
                "public", "patient",
                List.of(
                        new ColumnMetadata("id", Types.BIGINT, "bigint", 0, false, true),
                        new ColumnMetadata("name", Types.VARCHAR, "varchar", 64, true, false)),
                List.of("id"),
                List.of());
    }

    @Test
    void duplicateBatchWithSameHashSkipsBusinessWrite() throws Exception {
        when(adapter.readTableMetadata(any(), any(), any())).thenReturn(patientMetadata());
        when(adapter.hasUniqueConstraint(any(), any())).thenReturn(true);
        when(receiptRepository.findByBatch(any(), any(), any())).thenReturn(Optional.of(
                new BatchReceipt(SOURCE.toString(), "11111111-1111-1111-1111-111111111111",
                        "t", "r", 1, "hash-abc", Instant.now())));

        BatchReceiveService.ReceiveResult result = service.receiveWithConnection(
                connection, adapter, payload("11111111-1111-1111-1111-111111111111"),
                List.of("id"), "hash-abc");

        assertThat(result.duplicate()).isTrue();
        verify(batchWriter, never()).upsert(any(), any(), any(), any(), any(), any(), any(), any());
        verify(receiptRepository, never()).insert(any(), any());
        verify(connection, never()).commit();
    }

    @Test
    void sameBatchWithDifferentHashReturnsConflict() throws Exception {
        when(adapter.readTableMetadata(any(), any(), any())).thenReturn(patientMetadata());
        when(adapter.hasUniqueConstraint(any(), any())).thenReturn(true);
        when(receiptRepository.findByBatch(any(), any(), any())).thenReturn(Optional.of(
                new BatchReceipt(SOURCE.toString(), "22222222-2222-2222-2222-222222222222",
                        "t", "r", 1, "hash-old", Instant.now())));

        assertThatThrownBy(() -> service.receiveWithConnection(
                connection, adapter, payload("22222222-2222-2222-2222-222222222222"),
                List.of("id"), "hash-new"))
                .isInstanceOf(BatchReceiveService.ReceiveException.class)
                .satisfies(ex -> {
                    BatchReceiveService.ReceiveException receiveException =
                            (BatchReceiveService.ReceiveException) ex;
                    assertThat(receiveException.errorCode()).isEqualTo("BATCH_HASH_CONFLICT");
                    assertThat(receiveException.httpStatus()).isEqualTo(409);
                });
    }

    @Test
    void firstReceiveWritesBusinessAndReceiptInSameTransaction() throws Exception {
        when(adapter.readTableMetadata(any(), any(), any())).thenReturn(patientMetadata());
        when(adapter.hasUniqueConstraint(any(), any())).thenReturn(true);
        when(receiptRepository.findByBatch(any(), any(), any())).thenReturn(Optional.empty());

        BatchReceiveService.ReceiveResult result = service.receiveWithConnection(
                connection, adapter, payload("33333333-3333-3333-3333-333333333333"),
                List.of("id"), "hash-abc");

        assertThat(result.duplicate()).isFalse();
        assertThat(result.status()).isEqualTo("SUCCESS");
        verify(connection).setAutoCommit(false);
        verify(batchWriter).upsert(any(), any(), any(), any(), any(), any(), any(), any());
        verify(receiptRepository).insert(any(), any());
        verify(connection).commit();
    }

    @Test
    void writerFailureRollsBackBusinessAndReceipt() throws Exception {
        when(adapter.readTableMetadata(any(), any(), any())).thenReturn(patientMetadata());
        when(adapter.hasUniqueConstraint(any(), any())).thenReturn(true);
        when(receiptRepository.findByBatch(any(), any(), any())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new java.sql.SQLException("目标约束冲突"))
                .when(batchWriter).upsert(any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.receiveWithConnection(
                connection, adapter, payload("44444444-4444-4444-4444-444444444444"),
                List.of("id"), "hash-abc"))
                .isInstanceOf(BatchReceiveService.ReceiveException.class);

        verify(connection).rollback();
        verify(receiptRepository, never()).insert(any(), any());
    }

    @Test
    void missingUniqueConstraintIsRejectedBeforeTransaction() throws Exception {
        when(adapter.readTableMetadata(any(), any(), any())).thenReturn(patientMetadata());
        when(adapter.hasUniqueConstraint(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.receiveWithConnection(
                connection, adapter, payload("55555555-5555-5555-5555-555555555555"),
                List.of("name"), "hash-abc"))
                .isInstanceOf(BatchReceiveService.ReceiveException.class)
                .satisfies(ex -> assertThat(((BatchReceiveService.ReceiveException) ex).errorCode())
                        .isEqualTo("TARGET_UNIQUE_CONSTRAINT_MISSING"));

        verify(connection, never()).setAutoCommit(false);
    }

    /** 真实数据库幂等契约测试（三方向 E2E 时启用）。 */
    @EnabledIfSystemProperty(named = "contract.db.url", matches = ".+")
    @Test
    void realDatabaseUpsertAndReceiptIdempotency() throws Exception {
        String url = System.getProperty("contract.db.url");
        String username = System.getProperty("contract.db.username", "");
        String password = System.getProperty("contract.db.password", "");
        try (Connection realConnection = DriverManager.getConnection(url, username, password)) {
            // 建回执表并验证幂等写入（真实目标库）
            TargetDatabaseAdapter realAdapter = new com.mic.datasync.database.opengauss.OpenGaussTargetAdapter();
            try (var statement = realConnection.createStatement()) {
                statement.execute(realAdapter.receiptInitializationDdl());
            }
            assertThat(realAdapter.receiptTableExists(realConnection)).isTrue();
        }
    }
}
