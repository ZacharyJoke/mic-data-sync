package com.mic.datasync.webapi;

import com.mic.datasync.sink.BatchReceiveService;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sink 数据接收接口（Authorization: Bearer Token 认证）。
 */
@RestController
@RequestMapping("/data")
public class SinkDataController {

    private final BatchReceiveService batchReceiveService;

    public SinkDataController(BatchReceiveService batchReceiveService) {
        this.batchReceiveService = batchReceiveService;
    }

    /**
     * 接收一批数据并按唯一 Key UPSERT 到目标表。
     *
     * @param table       目标表名（请求体 target 中可带 schema）
     * @param payloadHash 传输负载 Hash（X-Payload-Hash 请求头，幂等依据）
     * @param request     UPSERT 唯一 Key + BatchPayload
     */
    @PostMapping("/receive/{table}")
    public ResponseEntity<?> receive(@PathVariable String table,
                                     @RequestHeader(value = "X-Payload-Hash", required = false) String payloadHash,
                                     @RequestBody SinkReceiveRequest request) {
        try {
            BatchReceiveService.ReceiveResult result = batchReceiveService.receive(
                    request.payload(), request.uniqueKeys(), payloadHash);
            return ResponseEntity.ok(result);
        } catch (BatchReceiveService.ReceiveException ex) {
            return ResponseEntity.status(HttpStatus.resolve(ex.httpStatus()))
                    .body(errorBody(ex.errorCode(), ex.getMessage()));
        }
    }

    private Map<String, Object> errorBody(String code, String message) {
        return Map.of("code", code, "message", message, "requestId", UUID.randomUUID().toString(), "details", Map.of());
    }

    /** 数据接收请求：UPSERT 唯一 Key（可空）+ 批次负载。 */
    public record SinkReceiveRequest(List<String> uniqueKeys, BatchPayload payload) {
    }
}
