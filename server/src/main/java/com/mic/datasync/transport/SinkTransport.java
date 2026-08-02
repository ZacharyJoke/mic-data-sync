package com.mic.datasync.transport;

import com.mic.datasync.shared.id.Identifiers;
import com.mic.datasync.transport.SinkResponseClassifier.Outcome;
import com.mic.datasync.transport.protocol.BatchPayload;

import java.util.List;
import java.util.Optional;

/**
 * Sink 传输接口：发送批次与查询回执。
 */
public interface SinkTransport {

    /** 发送批次到 Sink。 */
    SendResult send(SendRequest request);

    /** 查询批次回执；网络不可达返回空（保持 UNKNOWN）。 */
    Optional<ReceiptQueryResult> queryReceipt(ReceiptQueryRequest request);

    /** 发送请求。 */
    record SendRequest(
            String sinkUrl,
            String token,
            BatchPayload payload,
            List<String> uniqueKeys,
            String payloadHash,
            String contentEncoding) {
    }

    /** 发送结果（携带脱敏诊断信息）。 */
    record SendResult(Outcome outcome, Integer httpStatus, String errorCode, String message, long durationMs) {
    }

    /** 回执查询请求。 */
    record ReceiptQueryRequest(
            String sinkUrl,
            String token,
            Identifiers.InstanceId sourceInstanceId,
            Identifiers.BatchId batchId,
            String targetDataSourceId) {
    }

    /** 回执查询结果。 */
    record ReceiptQueryResult(boolean found, String payloadHash) {
    }
}
