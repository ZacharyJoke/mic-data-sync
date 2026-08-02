package com.mic.datasync.sink;

import com.mic.datasync.storage.secret.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Sink 访问令牌：生成、加密存储、掩码展示与轮换。
 *
 * <p>令牌以 AES-GCM 加密写入 SQLite（sink_token 单行），API/日志只展示掩码；
 * 校验使用恒定时间比较，避免时序侧信道。</p>
 */
@Service
public class SinkTokenService {

    private static final String PREFIX = "mic_";
    private static final int TOKEN_BYTES = 32;

    private final JdbcTemplate jdbcTemplate;
    private final SecretCipher secretCipher;
    private final SecureRandom secureRandom = new SecureRandom();

    public SinkTokenService(JdbcTemplate jdbcTemplate, SecretCipher secretCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretCipher = secretCipher;
    }

    /** 生成并保存新令牌（轮换），返回明文令牌（仅本次返回）。 */
    @Transactional
    public String generateAndSave() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = PREFIX + HexFormat.of().formatHex(bytes);
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT OR REPLACE INTO sink_token (id, token_enc, token_display, created_at, updated_at)
                VALUES (1, ?, ?, COALESCE((SELECT created_at FROM sink_token WHERE id = 1), ?), ?)
                """, secretCipher.encrypt(token), mask(token), now, now);
        return token;
    }

    /** 当前令牌（解密）。 */
    public Optional<String> currentToken() {
        return jdbcTemplate.query("SELECT token_enc FROM sink_token WHERE id = 1",
                        (rs, rowNum) -> rs.getString("token_enc"))
                .stream().findFirst().map(secretCipher::decrypt);
    }

    /** 掩码展示（如 mic_****a1b2），不返回明文。 */
    public Optional<String> display() {
        return jdbcTemplate.query("SELECT token_display FROM sink_token WHERE id = 1",
                        (rs, rowNum) -> rs.getString("token_display"))
                .stream().findFirst();
    }

    /** 校验令牌（恒定时间比较）。 */
    public boolean validate(String candidate) {
        if (candidate == null) {
            return false;
        }
        Optional<String> current = currentToken();
        if (current.isEmpty()) {
            return false;
        }
        byte[] expected = current.get().getBytes(StandardCharsets.UTF_8);
        byte[] actual = candidate.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /** 掩码：保留前缀与后 4 位。 */
    private String mask(String token) {
        int tail = Math.min(4, token.length() - PREFIX.length());
        return PREFIX + "****" + token.substring(token.length() - tail);
    }
}
