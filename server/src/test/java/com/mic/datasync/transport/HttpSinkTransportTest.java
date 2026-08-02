package com.mic.datasync.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.transport.SinkResponseClassifier.Outcome;
import com.mic.datasync.transport.protocol.BatchPayload;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP Sink 传输测试：2xx/4xx/断网/超时/回执查询与请求头。
 */
class HttpSinkTransportTest {

    private HttpServer server;
    private HttpSinkTransport transport;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastPayloadHash = new AtomicReference<>();
    private final AtomicReference<String> lastContentEncoding = new AtomicReference<>();

    private static final Identifiers.InstanceId SOURCE = Identifiers.InstanceId.generate();
    private static final Identifiers.BatchId BATCH = Identifiers.BatchId.generate();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        transport = new HttpSinkTransport(objectMapper, new SinkResponseClassifier());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private BatchPayload payload() {
        return new BatchPayload(
                1, SOURCE, Identifiers.InstanceId.generate(),
                null,
                Identifiers.TaskId.generate(), Identifiers.RunId.generate(), BATCH, 1L,
                new BatchPayload.TargetTable("public", "patient"),
                List.of("id", "name"),
                List.of(List.of(1L, "张三")),
                new BatchPayload.CheckpointCandidate(Map.of("id", 1L)));
    }

    private void registerReceiveHandler(int status, String body) {
        server.createContext("/data/receive/patient", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPayloadHash.set(exchange.getRequestHeaders().getFirst("X-Payload-Hash"));
            lastContentEncoding.set(exchange.getRequestHeaders().getFirst("Content-Encoding"));
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
            exchange.close();
        });
    }

    @Test
    void twoHundredIsConfirmed() {
        registerReceiveHandler(200, "{\"status\":\"SUCCESS\"}");
        SinkTransport.SendResult result = transport.send(new SinkTransport.SendRequest(
                baseUrl(), "token-abc", payload(), List.of("id"), "hash-1", "IDENTITY"));

        assertThat(result.outcome()).isEqualTo(Outcome.CONFIRMED);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(lastAuthorization.get()).isEqualTo("Bearer token-abc");
        assertThat(lastPayloadHash.get()).isEqualTo("hash-1");
        assertThat(lastContentEncoding.get()).isEqualTo("IDENTITY");
    }

    @Test
    void fourHundredIsBusinessError() {
        registerReceiveHandler(409, "{\"code\":\"BATCH_HASH_CONFLICT\"}");
        SinkTransport.SendResult result = transport.send(new SinkTransport.SendRequest(
                baseUrl(), "token", payload(), List.of("id"), "hash", "IDENTITY"));

        assertThat(result.outcome()).isEqualTo(Outcome.BUSINESS_ERROR);
        assertThat(result.httpStatus()).isEqualTo(409);
    }

    @Test
    void connectionRefusedIsUnknown() {
        // 端口未监听
        SinkTransport.SendResult result = transport.send(new SinkTransport.SendRequest(
                "http://127.0.0.1:1", "token", payload(), List.of("id"), "hash", "IDENTITY"));

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(result.errorCode()).isEqualTo("TRANSPORT_UNAVAILABLE");
    }

    @Test
    void receiptQueryFoundReturnsHash() throws Exception {
        server.createContext("/data/receipt/" + SOURCE + "/" + BATCH, exchange -> {
            byte[] response = "{\"found\":true,\"payloadHash\":\"hash-abc\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
            exchange.close();
        });

        Optional<SinkTransport.ReceiptQueryResult> result = transport.queryReceipt(
                new SinkTransport.ReceiptQueryRequest(baseUrl(), "token", SOURCE, BATCH, null));

        assertThat(result).isPresent();
        assertThat(result.get().found()).isTrue();
        assertThat(result.get().payloadHash()).isEqualTo("hash-abc");
    }

    @Test
    void receiptQueryNotFoundReturnsFoundFalse() {
        server.createContext("/data/receipt/" + SOURCE + "/" + BATCH, exchange -> {
            byte[] response = "{\"found\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
            exchange.close();
        });

        Optional<SinkTransport.ReceiptQueryResult> result = transport.queryReceipt(
                new SinkTransport.ReceiptQueryRequest(baseUrl(), "token", SOURCE, BATCH, null));

        assertThat(result).isPresent();
        assertThat(result.get().found()).isFalse();
    }

    @Test
    void receiptQueryNetworkFailureReturnsEmpty() {
        Optional<SinkTransport.ReceiptQueryResult> result = transport.queryReceipt(
                new SinkTransport.ReceiptQueryRequest("http://127.0.0.1:1", "token", SOURCE, BATCH, null));
        assertThat(result).isEmpty();
    }
}
