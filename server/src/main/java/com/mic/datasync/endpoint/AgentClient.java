package com.mic.datasync.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.database.ConnectionFactory.ConnectionTestResult;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.sink.SinkHandshakeService;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制台到远端端点的 Agent API 客户端。
 */
@Component
public class AgentClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public AgentClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<AgentProtocol.DataSourceInfo> listDataSources(EndpointRecord endpoint, DatabaseRole role) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            endpoint.baseUrl() + "/api/v1/agent/data-sources?role=" + role.name()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + endpoint.sinkToken())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Agent 返回 " + response.statusCode());
            }
            AgentProtocol.DataSourceInfo[] items = objectMapper.readValue(
                    response.body(), AgentProtocol.DataSourceInfo[].class);
            return Arrays.asList(items);
        } catch (Exception ex) {
            throw new IllegalStateException("远端数据源列表获取失败: " + safeMessage(ex), ex);
        }
    }

    public void create(EndpointRecord endpoint, DatabaseRole role,
                       String id, String name, String product, String jdbcUrl,
                       String username, String password, String driverType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("role", role.name());
        body.put("name", name);
        body.put("product", product);
        body.put("jdbcUrl", jdbcUrl);
        body.put("username", username);
        body.put("password", password);
        body.put("driverType", driverType);
        send(endpoint, "POST", "/api/v1/agent/data-sources", body);
    }

    public void update(EndpointRecord endpoint, String id, String name, String product, String jdbcUrl,
                       String username, String password, String driverType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("product", product);
        body.put("jdbcUrl", jdbcUrl);
        body.put("username", username);
        body.put("password", password);
        body.put("driverType", driverType);
        send(endpoint, "PUT", "/api/v1/agent/data-sources/" + id, body);
    }

    public void delete(EndpointRecord endpoint, String id) {
        send(endpoint, "DELETE", "/api/v1/agent/data-sources/" + id, null);
    }

    public ConnectionTestResult test(EndpointRecord endpoint, DatabaseRole role, String id, String name,
                                     String product, String jdbcUrl, String username, String password,
                                     String driverType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("role", role.name());
        body.put("name", name);
        body.put("product", product);
        body.put("jdbcUrl", jdbcUrl);
        body.put("username", username);
        body.put("password", password);
        body.put("driverType", driverType);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            endpoint.baseUrl() + "/api/v1/agent/data-sources/test"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + endpoint.sinkToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new ConnectionTestResult(false, null, null, null, null, "AGENT_ERROR",
                        null, "Agent 返回 " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), ConnectionTestResult.class);
        } catch (Exception ex) {
            return new ConnectionTestResult(false, null, null, null, null, "AGENT_UNREACHABLE",
                    null, "远端不可达: " + safeMessage(ex));
        }
    }

    /** 下发目标表预检到远端 Sink。 */
    public AgentProtocol.TargetPreflightResponse validateTarget(
            EndpointRecord endpoint,
            AgentProtocol.TargetPreflightRequest request) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(
                            endpoint.baseUrl() + "/api/v1/agent/target/preflight"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + endpoint.sinkToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("目标预检 Agent 返回 " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), AgentProtocol.TargetPreflightResponse.class);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("远端目标预检失败: " + safeMessage(ex), ex);
        }
    }

    /** 查询远端 Sink 令牌掩码状态。 */
    public AgentProtocol.SinkTokenInfo getSinkToken(EndpointRecord endpoint) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(
                            endpoint.baseUrl() + "/api/v1/agent/sink-token"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + endpoint.sinkToken())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Sink 令牌查询 Agent 返回 " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), AgentProtocol.SinkTokenInfo.class);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("远端 Sink 令牌查询失败: " + safeMessage(ex), ex);
        }
    }

    /** 用 Sink 访问令牌调用远端握手，返回 HTTP 状态码（异常返回 -1）。 */
    public int handshakeStatus(EndpointRecord endpoint, String token) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(
                            endpoint.baseUrl() + "/api/v1/sink/handshake"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return response.statusCode();
        } catch (Exception ex) {
            return -1;
        }
    }

    /** 用 Sink 访问令牌获取远端完整握手信息。 */
    public SinkHandshakeService.HandshakeResponse handshake(EndpointRecord endpoint, String token) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(
                            endpoint.baseUrl() + "/api/v1/sink/handshake"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("远端握手返回 " + response.statusCode());
            }
            return objectMapper.readValue(response.body(), SinkHandshakeService.HandshakeResponse.class);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("远端握手失败: " + safeMessage(ex), ex);
        }
    }

    private void send(EndpointRecord endpoint, String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + endpoint.sinkToken());
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Agent 返回 " + response.statusCode());
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("远端操作失败: " + safeMessage(ex), ex);
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }
}
