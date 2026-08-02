package com.mic.datasync.sink;

import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.endpoint.EndpointRecord;
import com.mic.datasync.endpoint.EndpointService;
import com.mic.datasync.storage.secret.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Source 端访问 Sink 的令牌配置。
 *
 * <p>支持在系统管理页直接替换并即时生效，无需重启；未配置时回退到部署配置
 * {@code mic.sync.sink-token}（环境变量 {@code MIC_SYNC_SINK_TOKEN}）。</p>
 */
@Service
public class SourceSinkTokenService implements SinkTokenResolver {

    private static final String PREFIX = "mic_";

    private final JdbcTemplate jdbcTemplate;
    private final SecretCipher secretCipher;
    private final String configuredToken;
    private final EndpointService endpointService;
    private final SinkTokenService sinkTokenService;

    public SourceSinkTokenService(
            JdbcTemplate jdbcTemplate,
            SecretCipher secretCipher,
            @Value("${mic.sync.sink-token:}") String configuredToken,
            EndpointService endpointService,
            SinkTokenService sinkTokenService) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretCipher = secretCipher;
        this.configuredToken = configuredToken;
        this.endpointService = endpointService;
        this.sinkTokenService = sinkTokenService;
    }

    @Override
    public String resolve() {
        return currentToken().orElse(configuredToken);
    }

    /** 按 Sink 端解析：远程端配置了专用令牌时优先使用，否则回退全局令牌。 */
    @Override
    public String resolveForEndpoint(String sinkEndpointId) {
        if (sinkEndpointId != null && !sinkEndpointId.isBlank()) {
            EndpointRecord endpoint = endpointService.get(sinkEndpointId).orElse(null);
            if (endpoint != null && endpoint.role() == DatabaseRole.SINK) {
                if (endpoint.isSelf()) {
                    Optional<String> local = sinkTokenService.currentToken();
                    if (local.isPresent()) {
                        return local.get();
                    }
                } else if (endpoint.sinkToken() != null && !endpoint.sinkToken().isBlank()) {
                    return endpoint.sinkToken();
                }
            }
        }
        return resolve();
    }

    /** 数据库配置的当前令牌（明文）。 */
    public Optional<String> currentToken() {
        List<String> rows = jdbcTemplate.query(
                "SELECT token_enc FROM source_sink_token WHERE id = 1",
                (rs, rowNum) -> rs.getString("token_enc"));
        return rows.stream().findFirst().map(secretCipher::decrypt);
    }

    /** 数据库是否已配置令牌。 */
    public boolean configuredFromDb() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_sink_token WHERE id = 1", Long.class);
        return count != null && count > 0;
    }

    /** 掩码展示。 */
    public Optional<String> display() {
        List<String> rows = jdbcTemplate.query(
                "SELECT token_display FROM source_sink_token WHERE id = 1",
                (rs, rowNum) -> rs.getString("token_display"));
        return rows.stream().findFirst();
    }

    /** 保存新令牌（覆盖旧值，立即生效）。 */
    @Transactional
    public void save(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token 不能为空");
        }
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT OR REPLACE INTO source_sink_token (id, token_enc, token_display, created_at, updated_at)
                VALUES (1, ?, ?, COALESCE((SELECT created_at FROM source_sink_token WHERE id = 1), ?), ?)
                """, secretCipher.encrypt(token.trim()), mask(token.trim()), now, now);
    }

    /** 清除数据库令牌，回退到部署配置。 */
    @Transactional
    public void clear() {
        jdbcTemplate.update("DELETE FROM source_sink_token WHERE id = 1");
    }

    private String mask(String token) {
        int tail = Math.min(4, token.length() - PREFIX.length());
        return PREFIX + "****" + token.substring(token.length() - tail);
    }
}
