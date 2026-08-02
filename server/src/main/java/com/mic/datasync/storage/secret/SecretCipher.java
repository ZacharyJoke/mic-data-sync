package com.mic.datasync.storage.secret;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 基于 AES-GCM 的机密信息加解密（用于数据库密码、Sink Token 等）。
 *
 * <p>密文格式：Base64(12 字节随机 IV ‖ GCM 密文)，附带 128 位认证标签；
 * 每次加密使用新 IV，保证相同明文产生不同密文；任何篡改都会被认证失败拦截。</p>
 */
@Component
public class SecretCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final byte[] masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public SecretCipher(MasterKeyService masterKeyService) {
        this(masterKeyService.key());
    }

    /** 独立/测试使用：直接提供 32 字节 AES 密钥。 */
    public SecretCipher(byte[] masterKey) {
        if (masterKey == null || masterKey.length != MasterKeyService.KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("Master Key 必须是 32 字节");
        }
        this.masterKey = masterKey.clone();
    }

    /** 加密明文，返回 Base64 编码的密文（含 IV）。 */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("机密信息加密失败", ex);
        }
    }

    /** 解密 Base64 密文（含 IV）。密文被篡改或密钥不匹配时抛出异常。 */
    public String decrypt(String ciphertext) {
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext);
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度非法");
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("机密信息解密失败（密钥不匹配或密文被篡改）", ex);
        }
    }
}
