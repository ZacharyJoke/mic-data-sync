package com.mic.datasync.webapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.sink.BatchReceiveService;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sink 数据接收控制器测试：gzip 请求体解压、旧版本兼容回退与明文解析。
 */
@ExtendWith(MockitoExtension.class)
class SinkDataControllerTest {

    @Mock
    private BatchReceiveService batchReceiveService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SinkDataController controller() {
        return new SinkDataController(batchReceiveService, objectMapper);
    }

    private byte[] jsonBody() throws Exception {
        return objectMapper.writeValueAsBytes(new SinkDataController.SinkReceiveRequest(
                List.of("id"), "UPSERT",
                new BatchPayload(
                        1,
                        Identifiers.InstanceId.generate(),
                        Identifiers.InstanceId.generate(),
                        null,
                        Identifiers.TaskId.generate(),
                        Identifiers.RunId.generate(),
                        Identifiers.BatchId.generate(),
                        1L,
                        new BatchPayload.TargetTable("public", "patient"),
                        List.of("id"),
                        List.of(List.of(1L)),
                        new BatchPayload.CheckpointCandidate(Map.of()))));
    }

    private byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }

    @Test
    void gzipBodyIsDecompressedBeforeDispatch() throws Exception {
        when(batchReceiveService.receive(any(), any(), any(), eq("hash-1")))
                .thenReturn(new BatchReceiveService.ReceiveResult(false, "SUCCESS", "b", 1, "ok"));

        ResponseEntity<?> response = controller().receive("patient", "hash-1", "GZIP", gzip(jsonBody()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(batchReceiveService).receive(any(), any(), any(), eq("hash-1"));
    }

    @Test
    void legacyPlainBodyMarkedGzipFallsBackToPlain() throws Exception {
        when(batchReceiveService.receive(any(), any(), any(), eq("hash-2")))
                .thenReturn(new BatchReceiveService.ReceiveResult(false, "SUCCESS", "b", 1, "ok"));

        ResponseEntity<?> response = controller().receive("patient", "hash-2", "GZIP", jsonBody());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(batchReceiveService).receive(any(), any(), any(), eq("hash-2"));
    }

    @Test
    void plainBodyWithoutEncodingIsDispatched() throws Exception {
        when(batchReceiveService.receive(any(), any(), any(), eq("hash-3")))
                .thenReturn(new BatchReceiveService.ReceiveResult(false, "SUCCESS", "b", 1, "ok"));

        ResponseEntity<?> response = controller().receive("patient", "hash-3", null, jsonBody());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(batchReceiveService).receive(any(), any(), any(), eq("hash-3"));
    }
}
