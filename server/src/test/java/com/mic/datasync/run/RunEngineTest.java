package com.mic.datasync.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.instance.RoleProperties;
import com.mic.datasync.run.RunFailureService.FailureStage;
import com.mic.datasync.run.RunService.RunRecord;
import com.mic.datasync.run.domain.RunStatus;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.source.domain.SqlReadDefinition;
import com.mic.datasync.storage.spool.BatchSpoolStore;
import com.mic.datasync.task.TaskService.TaskRecord;
import com.mic.datasync.task.domain.TaskDefinition.LifecycleStatus;
import com.mic.datasync.task.domain.TaskDefinition.WriteMode;
import com.mic.datasync.transport.SinkTransport;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Run 引擎批次处理测试：确认、业务错误、UNKNOWN 回执分支。
 */
@ExtendWith(MockitoExtension.class)
class RunEngineTest {

    @Mock
    private BatchSpoolStore spoolStore;
    @Mock
    private SinkTransport transport;
    @Mock
    private RunService runService;
    @Mock
    private RunFailureService runFailureService;

    private RunEngine engine;

    private static final Identifiers.TaskId TASK_ID = Identifiers.TaskId.generate();
    private static final Identifiers.RunId RUN_ID = Identifiers.RunId.generate();
    private static final Identifiers.BatchId BATCH_ID = Identifiers.BatchId.generate();

    @BeforeEach
    void setUp() {
        engine = newEngine(() -> "test-token");
    }

    private RunEngine newEngine(com.mic.datasync.sink.SinkTokenResolver resolver) {
        RoleProperties properties = new RoleProperties("source,sink", "/tmp/x",
                new RoleProperties.Source(10, 1),
                new RoleProperties.Sink(1000, 16 * 1024 * 1024, false));
        return new RunEngine(null, null, null, null, null, null, null, null, null,
                spoolStore, transport, null, runService, runFailureService, properties, new ObjectMapper(),
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class), resolver);
    }

    private TaskRecord task() {
        return new TaskRecord(
                TASK_ID, "patient-sync", 1, LifecycleStatus.ENABLED, "SQL",
                new SqlReadDefinition("SELECT id FROM patient", "patient", List.of("id"), "fp",
                        List.of("id"), null),
                "public", "patient", WriteMode.UPSERT,
                List.of("id"), List.of(), "http://sink:19090", null,
                Identifiers.InstanceId.generate(),
                "self-source", "self-sink", null, "sink-default",
                Instant.now(), Instant.now());
    }

    private RunRecord run() {
        return new RunRecord(RUN_ID, TASK_ID, "patient-sync", 1, RunService.RunKind.INITIAL_FULL,
                RunStatus.RUNNING, null, Instant.now(), null, 0, 0, null);
    }

    private BatchPayload batch() {
        return new BatchPayload(
                1, Identifiers.InstanceId.generate(), Identifiers.InstanceId.generate(), null,
                TASK_ID, RUN_ID, BATCH_ID, 1L,
                new BatchPayload.TargetTable("public", "patient"),
                List.of("id"), List.of(List.of(1L)),
                new BatchPayload.CheckpointCandidate(Map.of("id", 1L)));
    }

    @Test
    void parsesOpenGaussDatabaseTimeFormat() {
        // openGauss CURRENT_TIMESTAMP 返回 "yyyy-MM-dd HH:mm:ss.SSSSSS+08"
        Instant parsed = engine.parseDatabaseTime("2026-08-01 11:35:39.450707+08");
        assertThat(parsed).isEqualTo(Instant.parse("2026-08-01T03:35:39.450707Z"));
    }

    @Test
    void parsesIsoDatabaseTimeFormat() {
        Instant parsed = engine.parseDatabaseTime("2026-08-01T03:35:39.450707Z");
        assertThat(parsed).isEqualTo(Instant.parse("2026-08-01T03:35:39.450707Z"));
    }

    @Test
    void confirmedOutcomeWritesSpoolAndSends() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                com.mic.datasync.transport.SinkResponseClassifier.Outcome.CONFIRMED, 200, null, null, 5));

        engine.processBatch(null, task(), run(), batch());

        verify(spoolStore).write(any(), any(), anyLong(), any(), any(), anyString());
        verify(transport).send(any());
    }

    @Test
    void businessErrorThrows() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                com.mic.datasync.transport.SinkResponseClassifier.Outcome.BUSINESS_ERROR, 409, null, null, 5));

        assertThatThrownBy(() -> engine.processBatch(null, task(), run(), batch()))
                .isInstanceOf(RunEngine.RunExecutionException.class)
                .hasMessageContaining("Sink 拒绝批次");
    }

    @Test
    void unknownWithMatchingReceiptConfirms() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        // 捕获实际传输 Hash，回执返回相同 Hash
        final String[] capturedHash = {null};
        when(transport.send(any())).thenAnswer(invocation -> {
            SinkTransport.SendRequest request = invocation.getArgument(0);
            capturedHash[0] = request.payloadHash();
            return new SinkTransport.SendResult(
                    com.mic.datasync.transport.SinkResponseClassifier.Outcome.UNKNOWN, null, null, null, 5);
        });
        when(transport.queryReceipt(any())).thenAnswer(invocation ->
                Optional.of(new SinkTransport.ReceiptQueryResult(true, capturedHash[0])));

        engine.processBatch(null, task(), run(), batch());
        verify(transport).queryReceipt(any());
    }

    @Test
    void unknownWithNoReceiptRetriesSameBatch() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        // 第一次 UNKNOWN，重试 CONFIRMED
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                        com.mic.datasync.transport.SinkResponseClassifier.Outcome.UNKNOWN, null, null, null, 5),
                new SinkTransport.SendResult(
                        com.mic.datasync.transport.SinkResponseClassifier.Outcome.CONFIRMED, 200, null, null, 5));
        when(transport.queryReceipt(any())).thenReturn(Optional.of(
                new SinkTransport.ReceiptQueryResult(false, null)));

        engine.processBatch(null, task(), run(), batch());

        verify(transport, times(2)).send(any());
    }

    @Test
    void unknownWithUnreachableReceiptThrows() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-test"), "hash", 10, "IDENTITY"));
        when(transport.send(any())).thenReturn(new SinkTransport.SendResult(
                com.mic.datasync.transport.SinkResponseClassifier.Outcome.UNKNOWN, null, null, null, 5));
        when(transport.queryReceipt(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> engine.processBatch(null, task(), run(), batch()))
                .isInstanceOf(RunEngine.RunExecutionException.class)
                .hasMessageContaining("回执不可达");
    }

    @Test
    void usesResolverTokenForSinkRequests() throws Exception {
        when(spoolStore.write(any(), any(), anyLong(), any(), any(), anyString())).thenReturn(
                new BatchSpoolStore.StoredBatch(java.nio.file.Path.of("/tmp/spool-token"), "hash", 10, "IDENTITY"));
        final String[] capturedToken = {null};
        when(transport.send(any())).thenAnswer(invocation -> {
            SinkTransport.SendRequest request = invocation.getArgument(0);
            capturedToken[0] = request.token();
            return new SinkTransport.SendResult(
                    com.mic.datasync.transport.SinkResponseClassifier.Outcome.CONFIRMED, 200, null, null, 5);
        });

        newEngine(() -> "resolved-token").processBatch(null, task(), run(), batch());

        assertThat(capturedToken[0]).isEqualTo("resolved-token");
    }

    @Test
    void internalFailureRecordsStructuredDiagnosisWithoutStackOrPayload() {
        when(runService.create(any(), anyString(), anyInt(), any(), any())).thenReturn(run());

        engine.execute(task(), RunService.RunKind.INITIAL_FULL);

        verify(runFailureService).record(argThat(failure ->
                failure.stage() == FailureStage.INTERNAL
                        && failure.errorCode().equals("INTERNAL_ERROR")
                        && !failure.summary().contains("Exception")
                        && !failure.impact().isBlank()));
        verify(runService).updateStatus(
                eq(RUN_ID), eq(RunStatus.FAILED), anyString(), anyLong(), anyLong());
    }
}
