package com.mic.datasync.storage.secret;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AES-GCM 加解密单元测试。
 */
class SecretCipherTest {

    private SecretCipher cipher;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[MasterKeyService.KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(key);
        // 直接构造（避免依赖文件系统 Master Key）
        cipher = new SecretCipher(key);
    }

    @Test
    void encryptThenDecryptReturnsOriginal() {
        String plaintext = "s3cr3t-p@ssword";
        String encrypted = cipher.encrypt(plaintext);

        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(encrypted).doesNotContain(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void samePlaintextProducesDifferentCiphertext() {
        String plaintext = "same-value";
        String first = cipher.encrypt(plaintext);
        String second = cipher.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(plaintext);
        assertThat(cipher.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        String encrypted = cipher.encrypt("sensitive-data");

        // 篡改密文中间一个字节
        byte[] payload = Base64.getDecoder().decode(encrypted);
        payload[payload.length / 2] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(payload);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ciphertextIsBase64OfIvPlusEncryptedBytes() {
        String plaintext = "hello";
        String encrypted = cipher.encrypt(plaintext);

        byte[] payload = Base64.getDecoder().decode(encrypted);
        // 12 字节 IV + 明文长度 + 16 字节 GCM 认证标签
        assertThat(payload.length).isEqualTo(12 + plaintext.getBytes(StandardCharsets.UTF_8).length + 16);
    }
}
