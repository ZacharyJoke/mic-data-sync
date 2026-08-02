package com.mic.datasync.database;

import com.mic.datasync.storage.secret.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 数据源档案存取：每条档案归属于一个端（sync_endpoint），密码以 AES-GCM 加密后
 * 写入 SQLite；任何日志与 API 响应都不得输出明文密码。
 *
 * <p>v1 约束：Source 档案只属于 self-source，本机运行时读取自身档案；
 * Sink 档案可属于本地 self-sink 或远程 Sink 端（远程档案由远程端保存，
 * 控制台只通过 Agent API 查看/维护）。</p>
 */
@Service
public class DatabaseConfigService {

    public static final String SELF_SOURCE_ENDPOINT_ID = "self-source";
    public static final String SELF_SINK_ENDPOINT_ID = "self-sink";

    private final JdbcTemplate jdbcTemplate;
    private final SecretCipher secretCipher;

    public DatabaseConfigService(JdbcTemplate jdbcTemplate, SecretCipher secretCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretCipher = secretCipher;
    }

    /** 列出指定端下的全部数据源档案。 */
    public List<DatabaseConfig> list(String endpointId) {
        return jdbcTemplate.query("""
                SELECT id, endpoint_id, name, product, jdbc_url, username, password_enc, driver_type, created_at, updated_at
                FROM data_source WHERE endpoint_id = ? ORDER BY created_at ASC, id ASC
                """, (rs, rowNum) -> toConfig(rs), endpointId);
    }

    /** 列出全部数据源档案（含远程端目录镜像）。 */
    public List<DatabaseConfig> listAll() {
        return jdbcTemplate.query("""
                SELECT id, endpoint_id, name, product, jdbc_url, username, password_enc, driver_type, created_at, updated_at
                FROM data_source ORDER BY created_at ASC, id ASC
                """, (rs, rowNum) -> toConfig(rs));
    }

    /** 列出本地端（Source 固定自身 / Sink 自身）的数据源档案。 */
    public List<DatabaseConfig> listSelf(DatabaseRole role) {
        return list(selfEndpointId(role));
    }

    /** 本地端默认档案（兼容单连接时代）；不存在时取该端最早创建的档案。 */
    public Optional<DatabaseConfig> getDefault(DatabaseRole role) {
        List<DatabaseConfig> profiles = listSelf(role);
        if (profiles.isEmpty()) {
            return Optional.empty();
        }
        String defaultId = role == DatabaseRole.SOURCE ? "source-default" : "sink-default";
        return profiles.stream()
                .filter(config -> defaultId.equals(config.id()))
                .findFirst()
                .or(() -> profiles.stream().findFirst());
    }

    /** 按 ID 读取数据源档案（密码解密后返回）。 */
    public Optional<DatabaseConfig> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        List<DatabaseConfig> rows = jdbcTemplate.query("""
                SELECT id, endpoint_id, name, product, jdbc_url, username, password_enc, driver_type, created_at, updated_at
                FROM data_source WHERE id = ?
                """, (rs, rowNum) -> toConfig(rs), id);
        return rows.stream().findFirst();
    }

    /** 按端读取默认档案（该端最早创建或默认命名）。 */
    public Optional<DatabaseConfig> getDefaultForEndpoint(String endpointId) {
        List<DatabaseConfig> profiles = list(endpointId);
        if (profiles.isEmpty()) {
            return Optional.empty();
        }
        return profiles.stream().findFirst();
    }

    /** 创建数据源档案；id 为空时自动生成（远程端档案由控制台生成 ID，保证两端一致）。 */
    @Transactional
    public DatabaseConfig create(String endpointId, String id, String name, DatabaseType type, String jdbcUrl,
                                 String username, String password, String driverType) {
        if (endpointId == null || endpointId.isBlank()) {
            throw new IllegalArgumentException("所属端不能为空");
        }
        validate(type, jdbcUrl, username, driverType);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("首次保存必须提供密码");
        }
        String resolvedName = resolveName(endpointId, name);
        String resolvedId = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        String now = Instant.now().toString();
        try {
            jdbcTemplate.update("""
                    INSERT INTO data_source
                        (id, endpoint_id, name, product, jdbc_url, username, password_enc, driver_type, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, resolvedId, endpointId, resolvedName, type.name(), jdbcUrl, username,
                    secretCipher.encrypt(password), driverType, now, now);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new IllegalArgumentException("同端下数据源名称已存在: " + resolvedName);
        }
        return get(resolvedId).orElseThrow();
    }

    /** 更新数据源档案；密码为空保留原密码，名称为空保留原名。 */
    @Transactional
    public DatabaseConfig update(String id, String name, DatabaseType type, String jdbcUrl,
                                 String username, String password, String driverType) {
        DatabaseConfig existing = get(id).orElseThrow(() -> new IllegalArgumentException("数据源不存在"));
        validate(type, jdbcUrl, username, driverType);
        String resolvedName = name == null || name.isBlank() ? existing.name() : name.trim();
        String encrypted;
        if (password == null || password.isBlank()) {
            encrypted = jdbcTemplate.queryForObject(
                    "SELECT password_enc FROM data_source WHERE id = ?", String.class, id);
        } else {
            encrypted = secretCipher.encrypt(password);
        }
        String now = Instant.now().toString();
        try {
            jdbcTemplate.update("""
                    UPDATE data_source
                    SET name = ?, product = ?, jdbc_url = ?, username = ?, password_enc = ?,
                        driver_type = ?, updated_at = ?
                    WHERE id = ?
                    """, resolvedName, type.name(), jdbcUrl, username, encrypted, driverType, now, id);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new IllegalArgumentException("同端下数据源名称已存在: " + resolvedName);
        }
        return get(id).orElseThrow();
    }

    /** 删除数据源档案；被任务引用时拒绝。 */
    @Transactional
    public boolean delete(String id) {
        if (get(id).isEmpty()) {
            return false;
        }
        Integer referenced = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM task
                WHERE source_data_source_id = ? OR target_data_source_id = ?
                """, Integer.class, id, id);
        if (referenced != null && referenced > 0) {
            throw new IllegalStateException("该数据源已被任务引用，不能删除");
        }
        return jdbcTemplate.update("DELETE FROM data_source WHERE id = ?", id) > 0;
    }

    private void validate(DatabaseType type, String jdbcUrl, String username, String driverType) {
        if (type == null) {
            throw new IllegalArgumentException("数据库类型不能为空");
        }
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("JDBC URL 不能为空");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
    }

    private String resolveName(String endpointId, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (!trimmed.isBlank()) {
            return trimmed;
        }
        return SELF_SOURCE_ENDPOINT_ID.equals(endpointId) ? "默认 Source 数据源" : "默认 Sink 数据源";
    }

    private String selfEndpointId(DatabaseRole role) {
        return role == DatabaseRole.SOURCE ? SELF_SOURCE_ENDPOINT_ID : SELF_SINK_ENDPOINT_ID;
    }

    private DatabaseConfig toConfig(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DatabaseConfig(
                rs.getString("id"),
                rs.getString("endpoint_id"),
                rs.getString("name"),
                SELF_SOURCE_ENDPOINT_ID.equals(rs.getString("endpoint_id"))
                        ? DatabaseRole.SOURCE : DatabaseRole.SINK,
                DatabaseType.valueOf(rs.getString("product")),
                rs.getString("jdbc_url"),
                rs.getString("username"),
                secretCipher.decrypt(rs.getString("password_enc")),
                rs.getString("driver_type"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }
}
