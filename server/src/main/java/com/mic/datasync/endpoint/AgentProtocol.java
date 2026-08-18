package com.mic.datasync.endpoint;

import com.mic.datasync.sink.SinkHandshakeService;
import com.mic.datasync.task.FieldMapping;

import java.util.List;

/**
 * 控制台与端点之间的 Agent 协议响应。
 */
public final class AgentProtocol {

    private AgentProtocol() {
    }

    /** 探活响应：实例身份 + Sink 握手 + 该端数据源目录。 */
    public record AgentProbeResponse(
            String instanceId,
            List<String> roles,
            SinkHandshakeService.HandshakeResponse sinkStatus,
            List<DataSourceInfo> dataSources) {
    }

    /** 数据源目录项（脱敏，不含密码）。 */
    public record DataSourceInfo(
            String id,
            String name,
            String role,
            String product,
            String jdbcUrl,
            String username,
            String driverType) {
    }

    /** 远端目标表预检请求。 */
    public record TargetPreflightRequest(
            String targetDataSourceId,
            String schema,
            String table,
            String writeMode,
            List<String> uniqueKeys,
            List<FieldMapping> fieldMappings,
            List<String> sourceColumns) {
    }

    /** 远端目标表预检响应。 */
    public record TargetPreflightResponse(boolean valid, List<PreflightIssue> issues) {
    }

    /** 远端目标库 Schema 列表。 */
    public record SchemaList(List<String> schemas) {
    }

    /** 远端指定 Schema 下的目标表列表。 */
    public record TableList(List<String> tables) {
    }

    /** 远端目标表元数据（字段/主键/唯一索引）。 */
    public record TargetTableMetadata(
            String schema,
            String table,
            List<TargetColumn> columns,
            List<String> primaryKeyColumns,
            List<List<String>> uniqueIndexes) {
    }

    /** 远端目标表字段。 */
    public record TargetColumn(String name, String typeName, boolean nullable, boolean primaryKey) {
    }

    /** 预检问题项。 */
    public record PreflightIssue(
            String severity,
            String code,
            String message,
            String field,
            String stage,
            String suggestedAction) {
    }

    /** Sink 令牌状态（掩码展示，不含明文）。 */
    public record SinkTokenInfo(boolean configured, String display) {
    }
}
