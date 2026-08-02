package com.mic.datasync.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.instance.InstanceService;
import com.mic.datasync.sink.SinkHandshakeService;
import com.mic.datasync.storage.secret.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 端注册表服务。
 *
 * <p>v1 约束：Source 端固定为当前实例（self-source），只可探活不可编辑/删除；
 * Sink 端可注册多个（本地 self-sink 自动存在，远程端通过 Agent API 纳管）。</p>
 */
@Service
public class EndpointService {

    public static final String SELF_SOURCE_ID = "self-source";
    public static final String SELF_SINK_ID = "self-sink";

    private static final String STATUS_UNKNOWN = "UNKNOWN";
    private static final String STATUS_READY = "READY";
    private static final String STATUS_NOT_READY = "NOT_READY";
    private static final String STATUS_UNREACHABLE = "UNREACHABLE";

    private final JdbcTemplate jdbcTemplate;
    private final SecretCipher secretCipher;
    private final InstanceService instanceService;
    private final SinkHandshakeService handshakeService;
    private final ObjectMapper objectMapper;
    private final int serverPort;
    private final HttpClient httpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public EndpointService(JdbcTemplate jdbcTemplate,
                           SecretCipher secretCipher,
                           InstanceService instanceService,
                           SinkHandshakeService handshakeService,
                           ObjectMapper objectMapper,
                           @Value("${server.port:19090}") int serverPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretCipher = secretCipher;
        this.instanceService = instanceService;
        this.handshakeService = handshakeService;
        this.objectMapper = objectMapper;
        this.serverPort = serverPort;
    }

    /** 列出指定角色端。 */
    public List<EndpointRecord> list(DatabaseRole role) {
        return jdbcTemplate.query("""
                SELECT id, name, role, base_url, instance_id, sink_token_enc, is_self,
                       status, last_probe_at, created_at, updated_at
                FROM sync_endpoint WHERE role = ? ORDER BY is_self DESC, created_at ASC
                """, (rs, rowNum) -> toRecord(rs), role.name());
    }

    /** 列出全部端。 */
    public List<EndpointRecord> listAll() {
        return jdbcTemplate.query("""
                SELECT id, name, role, base_url, instance_id, sink_token_enc, is_self,
                       status, last_probe_at, created_at, updated_at
                FROM sync_endpoint ORDER BY is_self DESC, created_at ASC
                """, (rs, rowNum) -> toRecord(rs));
    }

    /** 按 ID 读取端。 */
    public Optional<EndpointRecord> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        List<EndpointRecord> rows = jdbcTemplate.query("""
                SELECT id, name, role, base_url, instance_id, sink_token_enc, is_self,
                       status, last_probe_at, created_at, updated_at
                FROM sync_endpoint WHERE id = ?
                """, (rs, rowNum) -> toRecord(rs), id);
        return rows.stream().findFirst();
    }

    /** 创建 Sink 端（v1 只允许 Sink 端注册；Source 端固定为当前实例）。 */
    @Transactional
    public EndpointRecord create(String name, String baseUrl, String sinkToken) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("端名称不能为空");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("访问地址不能为空");
        }
        if (sinkToken == null || sinkToken.isBlank()) {
            throw new IllegalArgumentException("Sink 访问令牌不能为空，请在目标端系统管理中生成");
        }
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try {
            jdbcTemplate.update("""
                    INSERT INTO sync_endpoint
                        (id, name, role, base_url, instance_id, sink_token_enc, is_self,
                         status, last_probe_at, created_at, updated_at)
                    VALUES (?, ?, 'SINK', ?, NULL, ?, 0, ?, NULL, ?, ?)
                    """, id, name.trim(), normalizeBaseUrl(baseUrl), secretCipher.encrypt(sinkToken),
                    STATUS_UNKNOWN, now, now);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new IllegalArgumentException("同角色下端名称已存在: " + name);
        }
        return get(id).orElseThrow();
    }

    /** 更新 Sink 端；令牌为空保留原值。本地端仅支持更新管理令牌，不可改名/改地址。 */
    @Transactional
    public EndpointRecord update(String id, String name, String baseUrl, String sinkToken) {
        EndpointRecord existing = get(id).orElseThrow(() -> new IllegalArgumentException("端不存在"));
        if (existing.isSelf()) {
            if ((name != null && !name.isBlank() && !name.trim().equals(existing.name()))
                    || (baseUrl != null && !baseUrl.isBlank()
                    && !normalizeBaseUrl(baseUrl).equals(existing.baseUrl()))) {
                throw new IllegalArgumentException("本地端仅支持更新 Sink 访问令牌");
            }
            if (sinkToken == null || sinkToken.isBlank()) {
                throw new IllegalArgumentException("本地端更新需填写 Sink 访问令牌");
            }
            jdbcTemplate.update(
                    """
                    UPDATE sync_endpoint
                    SET sink_token_enc = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    secretCipher.encrypt(sinkToken),
                    Instant.now().toString(), id);
            return get(id).orElseThrow();
        }
        String resolvedName = name == null || name.isBlank() ? existing.name() : name.trim();
        String resolvedUrl = baseUrl == null || baseUrl.isBlank() ? existing.baseUrl() : normalizeBaseUrl(baseUrl);
        String sinkTokenEnc = sinkToken == null || sinkToken.isBlank()
                ? jdbcTemplate.queryForObject(
                        "SELECT sink_token_enc FROM sync_endpoint WHERE id = ?", String.class, id)
                : secretCipher.encrypt(sinkToken);
        String now = Instant.now().toString();
        try {
            jdbcTemplate.update("""
                    UPDATE sync_endpoint
                    SET name = ?, base_url = ?, sink_token_enc = ?, updated_at = ?
                    WHERE id = ?
                    """, resolvedName, resolvedUrl, sinkTokenEnc, now, id);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new IllegalArgumentException("同角色下端名称已存在: " + resolvedName);
        }
        return get(id).orElseThrow();
    }

    /** 删除 Sink 端；被数据源或任务引用时拒绝。 */
    @Transactional
    public boolean delete(String id) {
        EndpointRecord existing = get(id).orElse(null);
        if (existing == null) {
            return false;
        }
        if (existing.isSelf()) {
            throw new IllegalArgumentException("本地端不能删除");
        }
        Integer references = jdbcTemplate.queryForObject("""
                SELECT (SELECT COUNT(*) FROM data_source WHERE endpoint_id = ?)
                     + (SELECT COUNT(*) FROM task WHERE sink_endpoint_id = ?)
                """, Integer.class, id, id);
        if (references != null && references > 0) {
            throw new IllegalStateException("该端已被数据源或任务引用，不能删除");
        }
        return jdbcTemplate.update("DELETE FROM sync_endpoint WHERE id = ?", id) > 0;
    }

    /** 探活：自身端走本地握手/身份，远程端调用 Agent API。
     *  注意：不能放在事务内，否则同步自调时令牌校验会等待同一 SQLite 连接形成自锁。 */
    public ProbeResult probe(String id) {
        EndpointRecord endpoint = get(id).orElseThrow(() -> new IllegalArgumentException("端不存在"));
        String now = Instant.now().toString();
        if (endpoint.isSelf()) {
            String instanceId = instanceService.instanceId().toString();
            String status = STATUS_READY;
            String message = "本机探活成功";
            String baseUrl = endpoint.baseUrl();
            if (endpoint.role() == DatabaseRole.SINK && (baseUrl == null || baseUrl.isBlank())) {
                baseUrl = "http://127.0.0.1:" + serverPort;
            }
            if (endpoint.role() == DatabaseRole.SINK && instanceService.isSinkEnabled()) {
                SinkHandshakeService.HandshakeResponse handshake = handshakeService.handshake();
                status = handshake.capabilityStatus();
                message = "本机探活成功：" + handshake.message();
            }
            jdbcTemplate.update("""
                    UPDATE sync_endpoint
                    SET instance_id = ?, status = ?, last_probe_at = ?, updated_at = ?, base_url = ?
                    WHERE id = ?
                    """, instanceId, status, now, now, baseUrl, id);
            return new ProbeResult(message, get(id).orElseThrow());
        }

        String sinkToken = endpoint.sinkToken();
        if (sinkToken == null || sinkToken.isBlank()
                || endpoint.baseUrl() == null || endpoint.baseUrl().isBlank()) {
            jdbcTemplate.update(
                    "UPDATE sync_endpoint SET status = 'UNREACHABLE', last_probe_at = ?, updated_at = ? WHERE id = ?",
                    now, now, id);
            return new ProbeResult("端信息不完整（缺少访问地址或 Sink 访问令牌）", get(id).orElseThrow());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(endpoint.baseUrl() + "/api/v1/sink/handshake"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + sinkToken)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Agent 探活返回 " + response.statusCode());
            }
            SinkHandshakeService.HandshakeResponse probe = objectMapper.readValue(
                    response.body(), SinkHandshakeService.HandshakeResponse.class);
            String status = probe.capabilityStatus();
            jdbcTemplate.update("""
                    UPDATE sync_endpoint
                    SET instance_id = ?, status = ?, last_probe_at = ?, updated_at = ?
                    WHERE id = ?
                    """, probe.sinkInstanceId(), status, now, now, id);
            String message = "远端探活成功：" + probe.message();
            return new ProbeResult(message, get(id).orElseThrow());
        } catch (Exception ex) {
            jdbcTemplate.update(
                    "UPDATE sync_endpoint SET status = 'UNREACHABLE', last_probe_at = ?, updated_at = ? WHERE id = ?",
                    now, now, id);
            return new ProbeResult("远端不可达：" + safeMessage(ex), get(id).orElseThrow());
        }
    }

    /** 任务创建时解析 Sink 端地址与实例 ID。 */
    public EndpointRecord resolveForTask(String endpointId) {
        EndpointRecord endpoint = get(endpointId)
                .orElseThrow(() -> new IllegalArgumentException("Sink 端不存在"));
        if (endpoint.role() != DatabaseRole.SINK) {
            throw new IllegalArgumentException("所选端不是 Sink 端");
        }
        if (endpoint.baseUrl() == null || endpoint.baseUrl().isBlank()
                || endpoint.instanceId() == null || endpoint.instanceId().isBlank()) {
            throw new IllegalArgumentException("Sink 端未完成探活，请先探活并确认实例 ID");
        }
        return endpoint;
    }

    private EndpointRecord toRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EndpointRecord(
                rs.getString("id"),
                rs.getString("name"),
                DatabaseRole.valueOf(rs.getString("role")),
                rs.getString("base_url"),
                rs.getString("instance_id"),
                rs.getString("sink_token_enc") == null
                        ? null : secretCipher.decrypt(rs.getString("sink_token_enc")),
                rs.getInt("is_self") == 1,
                rs.getString("status"),
                rs.getString("last_probe_at") == null ? null : Instant.parse(rs.getString("last_probe_at")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private String normalizeBaseUrl(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    /** 探活结果。 */
    public record ProbeResult(String message, EndpointRecord endpoint) {
    }
}
