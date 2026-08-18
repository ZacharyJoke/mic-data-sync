package com.mic.datasync.webapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.sink.BatchReceiveService;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Sink 数据接收接口（Authorization: Bearer Token 认证）。
 */
@RestController
@RequestMapping("/data")
public class SinkDataController {

    private static final Logger log = LoggerFactory.getLogger(SinkDataController.class);

    private final BatchReceiveService batchReceiveService;
    private final ObjectMapper objectMapper;

    public SinkDataController(BatchReceiveService batchReceiveService, ObjectMapper objectMapper) {
        this.batchReceiveService = batchReceiveService;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收一批数据并按唯一 Key UPSERT 到目标表。
     *
     * @param table       目标表名（请求体 target 中可带 schema）
     * @param payloadHash 传输负载 Hash（X-Payload-Hash 请求头，幂等依据）
     * @param request     UPSERT 唯一 Key + 写入模式 + BatchPayload
     */
    @PostMapping("/receive/{table}")
    public ResponseEntity<?> receive(@PathVariable String table,
                                     @RequestHeader(value = "X-Payload-Hash", required = false) String payloadHash,
                                     @RequestHeader(value = "Content-Encoding", required = false) String contentEncoding,
                                     @RequestBody byte[] body) {
        try {
            SinkReceiveRequest request = objectMapper.readValue(
                    decodeBody(body, contentEncoding), SinkReceiveRequest.class);
            BatchReceiveService.ReceiveResult result = batchReceiveService.receive(
                    request.payload(), request.uniqueKeys(), request.writeMode(), payloadHash);
            return ResponseEntity.ok(result);
        } catch (BatchReceiveService.ReceiveException ex) {
            return ResponseEntity.status(HttpStatus.resolve(ex.httpStatus()))
                    .body(errorBody(ex.errorCode(), ex.getMessage()));
        } catch (IOException ex) {
            log.warn("sink 请求体解析失败 contentEncoding={} bodyBytes={}",
                    contentEncoding, body == null ? 0 : body.length);
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_FAILED", "批次请求体解析失败"));
        }
    }

    /**
     * 按 Content-Encoding 解压请求体。
     *
     * <p>兼容旧版本 Source：曾发送未压缩 JSON 但标记 {@code Content-Encoding: GZIP}，
     * 解压失败时回退为原文解析，避免升级窗口内跨版本批次失败。</p>
     */
    private byte[] decodeBody(byte[] body, String contentEncoding) throws IOException {
        if (contentEncoding != null && "GZIP".equalsIgnoreCase(contentEncoding)) {
            try {
                return gunzip(body);
            } catch (IOException ex) {
                return body;
            }
        }
        return body;
    }

    private byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private Map<String, Object> errorBody(String code, String message) {
        return Map.of("code", code, "message", message, "requestId", UUID.randomUUID().toString(), "details", Map.of());
    }

    /**
     * 数据接收请求：UPSERT 唯一 Key（可空）+ 写入模式（可空，旧版本未传时按覆盖更新处理）
     * + 批次负载。
     */
    public record SinkReceiveRequest(List<String> uniqueKeys, String writeMode, BatchPayload payload) {
    }
}
