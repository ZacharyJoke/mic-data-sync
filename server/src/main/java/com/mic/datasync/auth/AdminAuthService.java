package com.mic.datasync.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 管理员账号服务。
 *
 * <p>首次启动时若 admin_user 表为空且配置了 {@code mic.sync.admin.password}
 * （可用环境变量 {@code MIC_SYNC_ADMIN_PASSWORD} 提供），自动初始化管理员；
 * 已存在管理员时不会覆盖。密码使用自适应哈希（BCrypt）保存，不回显。</p>
 */
@Service
public class AdminAuthService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public AdminAuthService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    /** 首次启动初始化管理员；表非空或未配置密码时跳过。 */
    @Transactional
    public void initializeAdminIfNeeded() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_user", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        String username = environment.getProperty("mic.sync.admin.username", "admin");
        String password = environment.getProperty("mic.sync.admin.password", "");
        if (password.isBlank()) {
            log.warn("admin_user 表为空且未配置 MIC_SYNC_ADMIN_PASSWORD，跳过管理员初始化，无法登录");
            return;
        }
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO admin_user (username, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """, username, passwordEncoder.encode(password), now, now);
        log.info("已初始化管理员账号 username={}", username);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        List<AdminAccount> accounts = jdbcTemplate.query(
                "SELECT username, password_hash FROM admin_user WHERE username = ?",
                (rs, rowNum) -> new AdminAccount(rs.getString("username"), rs.getString("password_hash")),
                username);
        if (accounts.isEmpty()) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        AdminAccount account = accounts.get(0);
        return User.withUsername(account.username())
                .password(account.passwordHash())
                .roles("ADMIN")
                .build();
    }

    /** 数据库中的管理员账号记录。 */
    private record AdminAccount(String username, String passwordHash) {
    }
}
