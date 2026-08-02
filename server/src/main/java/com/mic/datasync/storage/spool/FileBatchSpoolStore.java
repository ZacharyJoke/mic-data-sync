package com.mic.datasync.storage.spool;

import com.mic.datasync.instance.RoleProperties;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.storage.secret.MasterKeyService;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件 Spool 存储（AES-GCM 加密）。
 *
 * <p>写入流程：加密传输字节 → 写 {@code .part} → fsync → 原子改名 {@code .payload}；
 * 目录固定为 {@code ${dataDir}/spool/{taskId}/{runId}/{sequence}-{batchId}.payload}。</p>
 */
@Component
public class FileBatchSpoolStore implements BatchSpoolStore {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String PART_SUFFIX = ".part";

    private final Path spoolRoot;
    private final byte[] masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public FileBatchSpoolStore(RoleProperties roleProperties, MasterKeyService masterKeyService) {
        this.spoolRoot = Path.of(roleProperties.dataDir(), "spool");
        this.masterKey = masterKeyService.key();
    }

    @Override
    public Path spoolRoot() {
        return spoolRoot;
    }

    @Override
    public Path spoolDirectory(Identifiers.TaskId taskId, Identifiers.RunId runId) {
        return spoolRoot.resolve(taskId.toString()).resolve(runId.toString());
    }

    @Override
    public Path spoolFile(Identifiers.TaskId taskId, Identifiers.RunId runId,
                          long sequence, Identifiers.BatchId batchId) {
        return spoolDirectory(taskId, runId).resolve(sequence + "-" + batchId + ".payload");
    }

    @Override
    public StoredBatch write(Identifiers.TaskId taskId, Identifiers.RunId runId,
                             long sequence, Identifiers.BatchId batchId,
                             byte[] payloadBytes, String contentEncoding) throws IOException {
        Path target = spoolFile(taskId, runId, sequence, batchId);
        Path part = target.resolveSibling(target.getFileName() + PART_SUFFIX);
        Files.createDirectories(target.getParent());
        byte[] encrypted;
        try {
            encrypted = encrypt(payloadBytes);
        } catch (GeneralSecurityException ex) {
            throw new IOException("Spool 加密失败", ex);
        }
        // 写 .part + fsync
        try (FileChannel channel = FileChannel.open(part,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(encrypted));
            channel.force(true);
        }
        // 原子改名
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return new StoredBatch(target, sha256(payloadBytes), payloadBytes.length, contentEncoding);
    }

    @Override
    public byte[] read(Identifiers.TaskId taskId, Identifiers.RunId runId,
                       long sequence, Identifiers.BatchId batchId) throws IOException {
        Path file = spoolFile(taskId, runId, sequence, batchId);
        if (!Files.exists(file)) {
            throw new IOException("Spool 文件不存在: " + file);
        }
        byte[] encrypted = Files.readAllBytes(file);
        try {
            return decrypt(encrypted);
        } catch (GeneralSecurityException ex) {
            throw new IOException("Spool 解密失败（密文损坏）: " + file, ex);
        }
    }

    @Override
    public void delete(Identifiers.TaskId taskId, Identifiers.RunId runId,
                       long sequence, Identifiers.BatchId batchId) throws IOException {
        Files.deleteIfExists(spoolFile(taskId, runId, sequence, batchId));
    }

    @Override
    public List<Path> listPartFiles(Identifiers.TaskId taskId, Identifiers.RunId runId) throws IOException {
        Path directory = spoolDirectory(taskId, runId);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(PART_SUFFIX)).toList();
        }
    }

    /** 加密：IV + GCM 密文。 */
    private byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        byte[] encrypted = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
    }

    /** 解密：IV + GCM 密文。 */
    private byte[] decrypt(byte[] payload) throws GeneralSecurityException {
        if (payload.length <= IV_LENGTH) {
            throw new GeneralSecurityException("密文长度非法");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte[] iv = new byte[IV_LENGTH];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return cipher.doFinal(encrypted);
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
