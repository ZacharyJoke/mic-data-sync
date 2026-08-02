package com.mic.datasync.database;

import com.mic.datasync.instance.RoleProperties;
import com.mic.datasync.storage.secret.SecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据库配置加密存储与连接测试集成测试。
 *
 * <p>真实 KingbaseES/openGauss 连接由三方向 E2E（Task 19）覆盖；
 * 本测试验证加密存储、脱敏响应与驱动缺失时的明确错误路径。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatabaseConnectionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private RoleProperties roleProperties;

    private static final String PASSWORD = "S3cret!p@ss";

    private static final org.springframework.test.web.servlet.request.RequestPostProcessor ADMIN =
            user("admin").roles("ADMIN");

    @Test
    void saveConfigStoresEncryptedPasswordInSqlite() throws Exception {
        putSource(PASSWORD).andExpect(status().isOk());

        String stored = jdbcTemplate.queryForObject(
                "SELECT password_enc FROM data_source WHERE endpoint_id = 'self-source' AND id = 'source-default'",
                String.class);
        assertThat(stored).isNotEqualTo(PASSWORD);
        assertThat(stored).doesNotContain(PASSWORD);
        assertThat(stored).doesNotContain("p@ss");
        // 加密值可解密还原
        assertThat(secretCipher.decrypt(stored)).isEqualTo(PASSWORD);
    }

    @Test
    void getConfigDoesNotExposePassword() throws Exception {
        putSource(PASSWORD).andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/v1/database/SOURCE").with(ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.product").value("OPEN_GAUSS"))
                .andExpect(jsonPath("$.username").value("sync_user"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(PASSWORD);
        assertThat(body).doesNotContain("password");
    }

    @Test
    void updateWithoutPasswordKeepsExistingPassword() throws Exception {
        putSource(PASSWORD).andExpect(status().isOk());

        // 不带密码更新：保留原密码
        mockMvc.perform(put("/api/v1/database/SOURCE").with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product":"OPEN_GAUSS","jdbcUrl":"jdbc:opengauss://db:5432/sync",
                                 "username":"sync_user","driverType":"opengauss"}"""))
                .andExpect(status().isOk());

        String stored = jdbcTemplate.queryForObject(
                "SELECT password_enc FROM data_source WHERE endpoint_id = 'self-source' AND id = 'source-default'",
                String.class);
        assertThat(secretCipher.decrypt(stored)).isEqualTo(PASSWORD);
    }

    @Test
    void updateWithNewPasswordReplacesIt() throws Exception {
        putSource(PASSWORD).andExpect(status().isOk());

        String newPassword = "N3w-p@ss!";
        putSource(newPassword).andExpect(status().isOk());

        String stored = jdbcTemplate.queryForObject(
                "SELECT password_enc FROM data_source WHERE endpoint_id = 'self-source' AND id = 'source-default'",
                String.class);
        assertThat(secretCipher.decrypt(stored)).isEqualTo(newPassword);
    }

    @Test
    void defaultProfileKeepsSingleRowAndMultipleProfilesAllowed() throws Exception {
        putSource("p1").andExpect(status().isOk());
        putSource("p2").andExpect(status().isOk());
        putSink("sink-pass").andExpect(status().isOk());

        List<String> sources = jdbcTemplate.queryForList(
                "SELECT id FROM data_source WHERE endpoint_id = 'self-source'", String.class);
        List<String> sinks = jdbcTemplate.queryForList(
                "SELECT id FROM data_source WHERE endpoint_id = 'self-sink'", String.class);
        assertThat(sources).hasSize(1);
        assertThat(sinks).hasSize(1);

        // 多档案：通过新接口再建一个 Source 数据源
        mockMvc.perform(post("/api/v1/data-sources").with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endpointId":"self-source","name":"备份库",
                                 "product":"OPEN_GAUSS","jdbcUrl":"jdbc:opengauss://db:5432/backup",
                                 "username":"sync_user","password":"p3","driverType":"opengauss"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endpointId").value("self-source"))
                .andExpect(jsonPath("$.name").value("备份库"));
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM data_source WHERE endpoint_id = 'self-source'", String.class)).hasSize(2);
    }

    @Test
    void testConnectionWithoutDriverReturnsClearError() throws Exception {
        // 驱动目录不存在：返回 ok=false + 明确错误码，不抛 500
        mockMvc.perform(post("/api/v1/database/SOURCE/test").with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product":"OPEN_GAUSS","jdbcUrl":"jdbc:opengauss://127.0.0.1:15432/sync",
                                 "username":"sync_user","password":"test","driverType":"opengauss"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorCode").value("DATABASE_CAPABILITY_BLOCKED"));
    }

    @Test
    void invalidProductIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/database/SOURCE").with(ADMIN).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"product":"ORACLE","jdbcUrl":"jdbc:oracle:thin:@//db:1521/xe",
                                 "username":"u","password":"p","driverType":"oracle"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unauthenticatedAccessToDatabaseApiReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/database/SOURCE"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void driverDirectoryResolutionUsesConfiguredDataDir() {
        Path driverDir = Path.of(roleProperties.dataDir(), "drivers");
        assertThat(driverDir.toString()).startsWith(System.getProperty("java.io.tmpdir"));
        assertThat(Files.isDirectory(driverDir)).isFalse();
    }

    private org.springframework.test.web.servlet.ResultActions putSource(String password) throws Exception {
        return mockMvc.perform(put("/api/v1/database/SOURCE").with(ADMIN).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"product":"OPEN_GAUSS","jdbcUrl":"jdbc:opengauss://db:5432/sync",
                         "username":"sync_user","password":"%s","driverType":"opengauss"}""".formatted(password)));
    }

    private org.springframework.test.web.servlet.ResultActions putSink(String password) throws Exception {
        return mockMvc.perform(put("/api/v1/database/SINK").with(ADMIN).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"product":"KINGBASE_ES","jdbcUrl":"jdbc:kingbase8://db:54321/sync",
                         "username":"sink_user","password":"%s","driverType":"kingbase8"}""".formatted(password)));
    }
}
