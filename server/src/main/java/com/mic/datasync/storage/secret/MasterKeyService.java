package com.mic.datasync.storage.secret;

import com.mic.datasync.instance.RoleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Master Key 管理。
 *
 * <ul>
 *   <li>默认在 {@code ${dataDir}/secret/master.key} 生成本地随机 256 位密钥（POSIX 权限 600）；</li>
 *   <li>支持通过环境变量 {@code MIC_SYNC_MASTER_KEY} 外部提供（Base64 编码的 32 字节）；</li>
 *   <li>Master Key 不写入日志、不通过 API 返回。</li>
 * </ul>
 */
@Service
public class MasterKeyService {

    private static final Logger log = LoggerFactory.getLogger(MasterKeyService.class);

    /** Master Key 长度（AES-256）。 */
    public static final int KEY_LENGTH_BYTES = 32;

    private final byte[] masterKey;

    public MasterKeyService(RoleProperties roleProperties, Environment environment) throws IOException {
        String external = environment.getProperty("mic.sync.master-key", "");
        if (!external.isBlank()) {
            this.masterKey = decodeExternalKey(external);
            log.info("使用外部提供的 Master Key（来源：环境变量 MIC_SYNC_MASTER_KEY）");
        } else {
            Path keyFile = Path.of(roleProperties.dataDir(), "secret", "master.key");
            this.masterKey = loadOrCreateLocalKey(keyFile);
            log.info("Master Key 已就绪：{}", keyFile);
        }
    }

    /**
     * 返回 Master Key 的副本，调用方不得直接修改。
     */
    public byte[] key() {
        return masterKey.clone();
    }

    /** 从本地文件加载或创建 Master Key。 */
    private byte[] loadOrCreateLocalKey(Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            byte[] bytes = Files.readAllBytes(keyFile);
            if (bytes.length != KEY_LENGTH_BYTES) {
                throw new IOException("Master Key 文件长度非法（期望 32 字节）: " + keyFile);
            }
            return bytes;
        }
        Files.createDirectories(keyFile.getParent());
        byte[] generated = new byte[KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(generated);
        Files.write(keyFile, generated);
        restrictFilePermissions(keyFile);
        return generated;
    }

    /** 解析外部 Base64 Master Key。 */
    private byte[] decodeExternalKey(String encoded) {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded.trim());
            if (bytes.length != KEY_LENGTH_BYTES) {
                throw new IllegalArgumentException("MIC_SYNC_MASTER_KEY 必须是 Base64 编码的 32 字节密钥");
            }
            return bytes;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("MIC_SYNC_MASTER_KEY 格式非法: " + ex.getMessage(), ex);
        }
    }

    /** 将密钥文件权限收紧为仅当前用户可读写（POSIX 平台；非 POSIX 忽略）。 */
    private void restrictFilePermissions(Path keyFile) {
        try {
            Files.setPosixFilePermissions(keyFile, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ex) {
            log.warn("无法收紧 Master Key 文件权限（当前平台可能不支持 POSIX）: {}", ex.getMessage());
        }
    }
}
