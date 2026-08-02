package com.mic.datasync.storage.spool;

import com.mic.datasync.shared.id.Identifiers;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 批次 Spool 存储接口。
 *
 * <p>目录固定为 {@code ${dataDir}/spool/{taskId}/{runId}/{sequence}-{batchId}.payload}；
 * 加密文件先写入 {@code .part}、fsync、原子改名后才允许创建 Batch=PENDING。</p>
 */
public interface BatchSpoolStore {

    /** Spool 根目录。 */
    Path spoolRoot();

    /** 批次 Spool 目录。 */
    Path spoolDirectory(Identifiers.TaskId taskId, Identifiers.RunId runId);

    /** 批次 Spool 文件路径。 */
    Path spoolFile(Identifiers.TaskId taskId, Identifiers.RunId runId,
                   long sequence, Identifiers.BatchId batchId);

    /**
     * 加密落盘传输字节，返回存储信息（SHA-256 为明文传输字节的哈希）。
     *
     * @param contentEncoding IDENTITY 或 GZIP
     */
    StoredBatch write(Identifiers.TaskId taskId, Identifiers.RunId runId,
                      long sequence, Identifiers.BatchId batchId,
                      byte[] payloadBytes, String contentEncoding) throws IOException;

    /** 读取并解密传输字节。 */
    byte[] read(Identifiers.TaskId taskId, Identifiers.RunId runId,
                long sequence, Identifiers.BatchId batchId) throws IOException;

    /** 删除批次文件。 */
    void delete(Identifiers.TaskId taskId, Identifiers.RunId runId,
                long sequence, Identifiers.BatchId batchId) throws IOException;

    /** 列出目录下残留的 .part 文件（未完成写入）。 */
    List<Path> listPartFiles(Identifiers.TaskId taskId, Identifiers.RunId runId) throws IOException;

    /** 存储信息。 */
    record StoredBatch(Path path, String sha256, long payloadSize, String contentEncoding) {
    }
}
