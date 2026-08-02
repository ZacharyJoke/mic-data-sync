package com.mic.datasync.webapi;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.sink.ReceiptRepository;
import com.mic.datasync.sink.ReceiptRepository.BatchReceipt;
import com.mic.datasync.shared.id.Identifiers;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;

/**
 * Sink 回执查询接口（Authorization: Bearer Token 认证）。
 *
 * <p>Source 在批次结果 UNKNOWN 时调用：回执存在返回 found=true 与 payloadHash，
 * 不存在返回 found=false（Source 可据此决定复用原 Spool 重发）。</p>
 */
@RestController
@RequestMapping("/data")
public class SinkReceiptController {

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final ReceiptRepository receiptRepository;

    public SinkReceiptController(DatabaseConfigService configService,
                                 ConnectionFactory connectionFactory,
                                 ReceiptRepository receiptRepository) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.receiptRepository = receiptRepository;
    }

    /** 查询批次回执。 */
    @GetMapping("/receipt/{sourceInstanceId}/{batchId}")
    public ResponseEntity<?> receipt(@PathVariable String sourceInstanceId, @PathVariable String batchId,
                                     @RequestParam(required = false) String targetDataSourceId) {
        DatabaseConfig config = resolveConfig(targetDataSourceId);
        if (config == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "found", false,
                    "error", "目标数据源不存在"));
        }
        try (Connection connection = connectionFactory.open(config)) {
            Optional<BatchReceipt> receipt = receiptRepository.findByBatch(connection, sourceInstanceId, batchId);
            return receipt.map(r -> ResponseEntity.ok(Map.of(
                            "found", true,
                            "payloadHash", r.payloadHash(),
                            "batchId", r.batchId())))
                    .orElseGet(() -> ResponseEntity.ok(Map.of("found", false)));
        } catch (Exception ex) {
            return ResponseEntity.status(500).body(Map.of("found", false, "error", "回执查询失败"));
        }
    }

    private DatabaseConfig resolveConfig(String targetDataSourceId) {
        if (targetDataSourceId != null && !targetDataSourceId.isBlank()) {
            return configService.get(targetDataSourceId)
                    .filter(config -> config.role() == DatabaseRole.SINK)
                    .orElse(null);
        }
        return configService.getDefault(DatabaseRole.SINK).orElse(null);
    }
}
