package com.mic.datasync.storage.spool;

import com.mic.datasync.instance.RoleProperties;
import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.storage.secret.MasterKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 加密 Spool 存储测试：往返一致性、原子写入、密文损坏与删除。
 */
class FileBatchSpoolStoreTest {

    @TempDir
    Path tempDir;

    private FileBatchSpoolStore store;

    private static final Identifiers.TaskId TASK = Identifiers.TaskId.generate();
    private static final Identifiers.RunId RUN = Identifiers.RunId.generate();
    private static final Identifiers.BatchId BATCH = Identifiers.BatchId.generate();
    private static final long SEQUENCE = 1L;

    @BeforeEach
    void setUp() {
        RoleProperties properties = new RoleProperties(
                "source,sink", tempDir.toString(), tempDir.resolve("drivers").toString(),
                new RoleProperties.Source(10, 1),
                new RoleProperties.Sink(1000, 16 * 1024 * 1024, false));
        MasterKeyService masterKeyService = mock(MasterKeyService.class);
        when(masterKeyService.key()).thenReturn(new byte[MasterKeyService.KEY_LENGTH_BYTES]);
        store = new FileBatchSpoolStore(properties, masterKeyService);
    }

    @Test
    void writeThenReadReturnsSamePayload() throws Exception {
        byte[] payload = "{\"rows\":[[1,\"张三\"]]}".getBytes(StandardCharsets.UTF_8);

        BatchSpoolStore.StoredBatch stored = store.write(TASK, RUN, SEQUENCE, BATCH, payload, "IDENTITY");
        byte[] loaded = store.read(TASK, RUN, SEQUENCE, BATCH);

        assertThat(loaded).isEqualTo(payload);
        assertThat(stored.path()).isEqualTo(store.spoolFile(TASK, RUN, SEQUENCE, BATCH));
        assertThat(stored.payloadSize()).isEqualTo(payload.length);
    }

    @Test
    void repeatedLoadProducesSameHashAndPayload() throws Exception {
        byte[] payload = "repeat-me".repeat(1000).getBytes(StandardCharsets.UTF_8);

        BatchSpoolStore.StoredBatch first = store.write(TASK, RUN, SEQUENCE, BATCH, payload, "GZIP");
        byte[] loadedOnce = store.read(TASK, RUN, SEQUENCE, BATCH);
        byte[] loadedTwice = store.read(TASK, RUN, SEQUENCE, BATCH);
        BatchSpoolStore.StoredBatch second = store.write(TASK, RUN, SEQUENCE, BATCH, payload, "GZIP");

        assertThat(loadedOnce).isEqualTo(loadedTwice);
        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(store.read(TASK, RUN, SEQUENCE, BATCH)).isEqualTo(payload);
    }

    @Test
    void noPartFileRemainsAfterWrite() throws Exception {
        store.write(TASK, RUN, SEQUENCE, BATCH, "payload".getBytes(StandardCharsets.UTF_8), "IDENTITY");

        List<Path> parts = store.listPartFiles(TASK, RUN);
        assertThat(parts).isEmpty();
    }

    @Test
    void tamperedFileFailsDecryption() throws Exception {
        store.write(TASK, RUN, SEQUENCE, BATCH, "sensitive".getBytes(StandardCharsets.UTF_8), "IDENTITY");
        Path file = store.spoolFile(TASK, RUN, SEQUENCE, BATCH);
        byte[] content = Files.readAllBytes(file);
        content[content.length / 2] ^= 0x01;
        Files.write(file, content);

        assertThatThrownBy(() -> store.read(TASK, RUN, SEQUENCE, BATCH))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void missingFileFailsRead() {
        assertThatThrownBy(() -> store.read(TASK, RUN, 999L, BATCH))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void deleteRemovesFile() throws Exception {
        store.write(TASK, RUN, SEQUENCE, BATCH, "x".getBytes(StandardCharsets.UTF_8), "IDENTITY");
        store.delete(TASK, RUN, SEQUENCE, BATCH);
        assertThat(Files.exists(store.spoolFile(TASK, RUN, SEQUENCE, BATCH))).isFalse();
    }

    @Test
    void directoryLayoutIsStable() {
        Path file = store.spoolFile(TASK, RUN, SEQUENCE, BATCH);
        assertThat(file.toString())
                .startsWith(tempDir.resolve("spool").toString())
                .contains(TASK.toString())
                .contains(RUN.toString())
                .endsWith(SEQUENCE + "-" + BATCH + ".payload");
        assertThat(Arrays.asList(file.getParent().toString().split("/"))).contains(TASK.toString(), RUN.toString());
    }
}
