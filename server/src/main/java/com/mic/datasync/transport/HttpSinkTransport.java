package com.mic.datasync.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.transport.protocol.BatchPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JDK HttpClient 的 Sink 传输实现。
 *
 * <p>请求携带 Bearer Token、sourceInstanceId、expectedSinkInstanceId、batchId、
 * payloadHash（X-Payload-Hash）与 Content-Encoding；日志只记录 requestId/batchId/
 * 行数/字节数/耗时/错误码，不记录 Payload 与 Token。</p>
 */
@Component
public class HttpSinkTransport implements SinkTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpSinkTransport.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper;
    private final SinkResponseClassifier classifier;
    private final HttpClient httpClient;

    public HttpSinkTransport(ObjectMapper objectMapper, SinkResponseClassifier classifier) {
        this.objectMapper = objectMapper;
        this.classifier = classifier;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public SendResult send(SendRequest request) {
        String batchId = request.payload().batchId().toString();
        int rowCount = request.payload().rows() == null ? 0 : request.payload().rows().size();
        long start = System.nanoTime();
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of(
                    "uniqueKeys", request.uniqueKeys() == null ? List.of() : request.uniqueKeys(),
                    "payload", request.payload()));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request.sinkUrl() + "/data/receive/"
                            + request.payload().target().table()))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + request.token())
                    .header("X-Payload-Hash", request.payloadHash() == null ? "" : request.payloadHash())
                    .header("Content-Encoding", request.contentEncoding() == null ? "IDENTITY" : request.contentEncoding())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            SinkResponseClassifier.Outcome outcome = classifier.classify(response.statusCode());
            log.info("sink-send batchId={} rows={} bytes={} status={} outcome={} durationMs={}",
                    batchId, rowCount, body.length, response.statusCode(), outcome, durationMs);
            return new SendResult(outcome, response.statusCode(), null, null, durationMs);
        } catch (IOException | InterruptedException ex) {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.warn("sink-send batchId={} rows={} outcome=UNKNOWN error={} durationMs={}",
                    batchId, rowCount, ex.getClass().getSimpleName(), durationMs);
            return new SendResult(SinkResponseClassifier.Outcome.UNKNOWN, null,
                    "TRANSPORT_UNAVAILABLE", "连接中断或超时", durationMs);
        }
    }

    @Override
    public Optional<ReceiptQueryResult> queryReceipt(ReceiptQueryRequest request) {
        String url = request.sinkUrl() + "/data/receipt/" + request.sourceInstanceId() + "/" + request.batchId();
        if (request.targetDataSourceId() != null && !request.targetDataSourceId().isBlank()) {
            url += "?targetDataSourceId=" + java.net.URLEncoder.encode(request.targetDataSourceId(), StandardCharsets.UTF_8);
        }
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + request.token())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<?, ?> body = objectMapper.readValue(response.body(), Map.class);
                boolean found = Boolean.TRUE.equals(body.get("found"));
                return Optional.of(new ReceiptQueryResult(found, found ? String.valueOf(body.get("payloadHash")) : null));
            }
            if (response.statusCode() == 404) {
                return Optional.of(new ReceiptQueryResult(false, null));
            }
            return Optional.empty();
        } catch (IOException | InterruptedException ex) {
            log.warn("sink-receipt-query batchId={} unreachable", request.batchId());
            return Optional.empty();
        }
    }
}
