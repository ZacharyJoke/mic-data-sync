package com.mic.datasync.task;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseDirection;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.SourceDatabaseAdapter;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.endpoint.AgentClient;
import com.mic.datasync.endpoint.AgentProtocol;
import com.mic.datasync.endpoint.EndpointRecord;
import com.mic.datasync.endpoint.EndpointService;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.source.TableReadPlanCompiler;
import com.mic.datasync.source.domain.ReadDefinition;
import com.mic.datasync.source.domain.ReadPlan.PaginationStrategy;
import com.mic.datasync.source.domain.SqlReadDefinition;
import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.source.sql.SqlMetadataInspector;
import com.mic.datasync.source.sql.SqlReadPlanCompiler;
import com.mic.datasync.source.sql.SqlSafetyValidator;
import com.mic.datasync.task.TaskService.TaskRecord;
import com.mic.datasync.task.domain.TaskDefinition.WriteMode;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 任务启用前完整校验。
 *
 * <p>覆盖：数据库方向、Sink URL/实例身份、源结构（Table/SQL 编译）、目标结构、
 * 字段映射与必填、类型兼容（警告）、分页唯一性、目标唯一约束、回执表 READY。</p>
 */
@Component
public class TaskValidator {

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final DatabaseAdapterFactory adapterFactory;
    private final TableReadPlanCompiler tableCompiler;
    private final SqlReadPlanCompiler sqlCompiler;
    private final SqlMetadataInspector sqlInspector;
    private final SqlSafetyValidator sqlSafetyValidator;
    private final EndpointService endpointService;
    private final AgentClient agentClient;

    public TaskValidator(DatabaseConfigService configService,
                         ConnectionFactory connectionFactory,
                         DatabaseAdapterFactory adapterFactory,
                         TableReadPlanCompiler tableCompiler,
                         SqlReadPlanCompiler sqlCompiler,
                         SqlMetadataInspector sqlInspector,
                         SqlSafetyValidator sqlSafetyValidator,
                         EndpointService endpointService,
                         AgentClient agentClient) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.adapterFactory = adapterFactory;
        this.tableCompiler = tableCompiler;
        this.sqlCompiler = sqlCompiler;
        this.sqlInspector = sqlInspector;
        this.sqlSafetyValidator = sqlSafetyValidator;
        this.endpointService = endpointService;
        this.agentClient = agentClient;
    }

    /** 校验报告。 */
    public record ValidationReport(boolean valid, List<Issue> issues) {

        public ValidationReport {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public static ValidationReport of(List<Issue> issues) {
            boolean valid = issues.stream().noneMatch(Issue::blocking);
            return new ValidationReport(valid, issues);
        }

        public String firstIssueMessage() {
            return issues.isEmpty() ? "" : issues.get(0).message();
        }

        public enum Severity {
            BLOCKING,
            WARNING
        }

        public enum ValidationStage {
            SOURCE_CONFIGURATION,
            SOURCE_VALIDATION,
            TARGET_CONFIGURATION,
            TARGET_VALIDATION,
            SINK_HANDSHAKE
        }

        public record Issue(
                Severity severity,
                String code,
                String message,
                String field,
                ValidationStage stage,
                String suggestedAction) {

            public boolean blocking() {
                return severity == Severity.BLOCKING;
            }
        }
    }

    /** 启用前完整校验（连接真实 Source/Target）。 */
    public ValidationReport validateForEnable(TaskRecord task) {
        List<ValidationReport.Issue> issues = new ArrayList<>();
        validateIncrementalCursorConfiguration(task.readDefinition(), task.writeMode(),
                task.uniqueKeys(), issues);

        DatabaseConfig sourceConfig = resolveSourceConfig(task);
        DatabaseConfig targetConfig = resolveTargetConfig(task);
        if (sourceConfig == null) {
            issues.add(error("VALIDATION_FAILED", "未配置 Source 数据库，请先在数据库管理中配置",
                    "source", ValidationReport.ValidationStage.SOURCE_CONFIGURATION,
                    "在数据库管理中配置 Source 连接后重试"));
        }
        if (targetConfig == null) {
            issues.add(error("VALIDATION_FAILED", "未配置 Sink（目标）数据库，请先在数据库管理中配置",
                    "sink", ValidationReport.ValidationStage.TARGET_CONFIGURATION,
                    "在数据库管理中配置 Sink 连接后重试"));
        }
        if (sourceConfig == null || targetConfig == null) {
            return ValidationReport.of(issues);
        }

        // 方向校验
        if (!DatabaseDirection.isSupported(sourceConfig.databaseType(), targetConfig.databaseType())) {
            issues.add(error("UNSUPPORTED_DATABASE_DIRECTION",
                    "不支持的同步方向: " + sourceConfig.databaseType() + " → " + targetConfig.databaseType(),
                    "direction", ValidationReport.ValidationStage.SOURCE_CONFIGURATION,
                    "调整源端或目标端数据库类型，使同步方向受支持"));
        }
        // Sink URL / 实例身份
        if (task.remoteSinkUrl() == null || task.remoteSinkUrl().isBlank()) {
            issues.add(error("VALIDATION_FAILED", "Sink URL 必填",
                    "remoteSinkUrl", ValidationReport.ValidationStage.SINK_HANDSHAKE,
                    "填写可访问的 http(s) Sink 地址"));
        } else if (!isHttpUrl(task.remoteSinkUrl())) {
            issues.add(error("VALIDATION_FAILED", "Sink URL 必须是 http:// 或 https://",
                    "remoteSinkUrl", ValidationReport.ValidationStage.SINK_HANDSHAKE,
                    "将 Sink URL 改为 http:// 或 https:// 开头"));
        }
        if (task.expectedSinkInstanceId() == null) {
            issues.add(error("VALIDATION_FAILED", "expectedSinkInstanceId 必填（来自 Sink 握手）",
                    "expectedSinkInstanceId", ValidationReport.ValidationStage.SINK_HANDSHAKE,
                    "先从 Sink 握手获取实例 ID 再填写"));
        }

        // 源结构校验
        validateSourceStructure(task, sourceConfig, issues);
        // 目标结构、映射、唯一约束、回执
        validateTargetStructure(task, targetConfig, issues);

        return ValidationReport.of(issues);
    }

    private DatabaseConfig resolveSourceConfig(TaskRecord task) {
        if (task.sourceDataSourceId() != null && !task.sourceDataSourceId().isBlank()) {
            return configService.get(task.sourceDataSourceId())
                    .filter(config -> config.role() == DatabaseRole.SOURCE)
                    .orElse(null);
        }
        return configService.getDefault(DatabaseRole.SOURCE).orElse(null);
    }

    private DatabaseConfig resolveTargetConfig(TaskRecord task) {
        if (task.targetDataSourceId() != null && !task.targetDataSourceId().isBlank()) {
            return configService.get(task.targetDataSourceId())
                    .filter(config -> config.role() == DatabaseRole.SINK)
                    .orElse(null);
        }
        return configService.getDefault(DatabaseRole.SINK).orElse(null);
    }

    /** 纯逻辑基础校验（无连接，可单测）：方向、URL、必填。 */
    public ValidationReport validateBasics(String remoteSinkUrl,
                                           DatabaseType sourceType,
                                           DatabaseType targetType,
                                           boolean sinkInstanceConfigured) {
        List<ValidationReport.Issue> issues = new ArrayList<>();
        if (sourceType == null || targetType == null) {
            issues.add(error("VALIDATION_FAILED", "Source/Sink 数据库类型缺失",
                    "direction", ValidationReport.ValidationStage.SOURCE_CONFIGURATION,
                    "先在数据库管理中配置 Source 与 Sink 类型"));
        } else if (!DatabaseDirection.isSupported(sourceType, targetType)) {
            issues.add(error("UNSUPPORTED_DATABASE_DIRECTION",
                    "不支持的同步方向: " + sourceType + " → " + targetType,
                    "direction", ValidationReport.ValidationStage.SOURCE_CONFIGURATION,
                    "调整源端或目标端数据库类型，使同步方向受支持"));
        }
        if (remoteSinkUrl == null || remoteSinkUrl.isBlank()) {
            issues.add(error("VALIDATION_FAILED", "Sink URL 必填",
                    "remoteSinkUrl", ValidationReport.ValidationStage.SINK_HANDSHAKE,
                    "填写可访问的 http(s) Sink 地址"));
        } else if (!isHttpUrl(remoteSinkUrl)) {
            issues.add(error("VALIDATION_FAILED", "Sink URL 必须是 http:// 或 https://",
                    "remoteSinkUrl", ValidationReport.ValidationStage.SINK_HANDSHAKE,
                    "将 Sink URL 改为 http:// 或 https:// 开头"));
        }
        if (!sinkInstanceConfigured) {
            issues.add(error("VALIDATION_FAILED", "expectedSinkInstanceId 必填",
                    "expectedSinkInstanceId", ValidationReport.ValidationStage.SINK_HANDSHAKE,
                    "先从 Sink 握手获取实例 ID 再填写"));
        }
        return ValidationReport.of(issues);
    }

    /** 字段映射校验（可单测）：目标字段存在、必填字段已映射、源字段存在。 */
    public void validateMappings(List<String> sourceColumns,
                                 TableMetadata targetMetadata,
                                 List<FieldMapping> mappings,
                                 List<ValidationReport.Issue> issues) {
        Set<String> sourceSet = lowerSet(sourceColumns);
        Set<String> targetSet = lowerSet(targetMetadata.columns().stream().map(ColumnMetadata::name).toList());
        Set<String> mappedTargets = new HashSet<>();
        for (FieldMapping mapping : mappings) {
            // 源字段为空列表表示 Table 模式"全字段"（编译后为元数据全字段），此时不做源字段存在性检查
            if (!sourceColumns.isEmpty() && !sourceSet.contains(mapping.sourceField().toLowerCase(Locale.ROOT))) {
                issues.add(error("VALIDATION_FAILED", "源字段不存在: " + mapping.sourceField(),
                        "fieldMappings." + mapping.sourceField(),
                        ValidationReport.ValidationStage.SOURCE_VALIDATION,
                        "重新加载源表字段并修正映射"));
            }
            if (!targetSet.contains(mapping.targetField().toLowerCase(Locale.ROOT))) {
                issues.add(error("VALIDATION_FAILED", "目标字段不存在: " + mapping.targetField(),
                        "fieldMappings." + mapping.sourceField(),
                        ValidationReport.ValidationStage.TARGET_VALIDATION,
                        "重新加载目标字段并修正映射"));
            }
            mappedTargets.add(mapping.targetField().toLowerCase(Locale.ROOT));
        }
        // 目标必填字段（非空）必须完成映射
        for (ColumnMetadata column : targetMetadata.columns()) {
            if (!column.nullable() && !mappedTargets.contains(column.name().toLowerCase(Locale.ROOT))) {
                issues.add(error("VALIDATION_FAILED", "目标必填字段未映射: " + column.name(),
                        "fieldMappings", ValidationReport.ValidationStage.TARGET_VALIDATION,
                        "为目标必填字段补充映射"));
            }
        }
    }

    /** 增量游标配置校验（可单测）：配置了更新时间字段的任务必须使用 UPSERT 写入模式。 */
    public void validateIncrementalCursorConfiguration(ReadDefinition definition,
                                                       WriteMode writeMode,
                                                       List<String> uniqueKeys,
                                                       List<ValidationReport.Issue> issues) {
        // REPLACE_ALL 仅支持全量：即使配置了更新时间字段也不允许增量
        if (writeMode == WriteMode.REPLACE_ALL) {
            if (definition.updatedTimeField() != null && !definition.updatedTimeField().isBlank()) {
                issues.add(error("REPLACE_ALL_NO_INCREMENT",
                        "REPLACE_ALL 仅支持全量同步，不能配置更新时间字段",
                        "readDefinition.updatedTimeField", ValidationReport.ValidationStage.SOURCE_VALIDATION,
                        "移除更新时间字段后重新保存"));
            }
            return;
        }
        if (definition.updatedTimeField() == null || definition.updatedTimeField().isBlank()) {
            return;
        }
        boolean conflictSafeMode = writeMode == WriteMode.UPSERT
                || writeMode == WriteMode.UPSERT_NO_OVERWRITE;
        if (!conflictSafeMode || uniqueKeys == null || uniqueKeys.isEmpty()) {
            issues.add(error("INCREMENTAL_REQUIRES_UPSERT",
                    "配置了更新时间字段的任务必须使用 UPSERT 或 UPSERT_NO_OVERWRITE 写入模式并配置唯一 Key",
                    "writeMode", ValidationReport.ValidationStage.TARGET_VALIDATION,
                    "将写入模式改为 UPSERT / UPSERT_NO_OVERWRITE 并配置唯一 Key"));
        }
    }

    private void validateSourceStructure(TaskRecord task, DatabaseConfig sourceConfig,
                                         List<ValidationReport.Issue> issues) {
        try (Connection connection = connectionFactory.open(sourceConfig)) {
            SourceDatabaseAdapter adapter = adapterFactory.sourceAdapter(sourceConfig.databaseType());
            ReadDefinition definition = task.readDefinition();
            if ("TABLE".equals(task.readMode()) && definition instanceof TableReadDefinition tableDefinition) {
                TableMetadata metadata = adapter.readTableMetadata(connection, tableDefinition.schema(), tableDefinition.table());
                PaginationStrategy strategy = task.writeMode() == WriteMode.REPLACE_ALL
                        ? PaginationStrategy.OFFSET : PaginationStrategy.KEYSET;
                try {
                    // softUniqueAccepted=true：唯一性由下方约束/实测校验把关
                    tableCompiler.compile(tableDefinition, metadata, strategy, true);
                } catch (IllegalArgumentException ex) {
                    issues.add(error("VALIDATION_FAILED", ex.getMessage(),
                            "readDefinition", ValidationReport.ValidationStage.SOURCE_VALIDATION,
                            "根据提示修正读取定义"));
                    return;
                }
                // 软唯一键实测：KEYSET 且分页键非约束组合时，验证组合唯一且非 NULL
                if (strategy == PaginationStrategy.KEYSET
                        && !tableDefinition.paginationKeys().isEmpty()
                        && !isConstrainedUniqueKey(metadata, tableDefinition.paginationKeys())) {
                    try {
                        boolean unique = adapter.columnGroupIsUnique(connection,
                                tableDefinition.schema(), tableDefinition.table(),
                                tableDefinition.paginationKeys());
                        if (!unique) {
                            issues.add(error("PAGINATION_KEY_NOT_UNIQUE",
                                    "分页 Key 组合在源表中不唯一（或含 NULL），不允许作为分页键: "
                                            + String.join(",", tableDefinition.paginationKeys()),
                                    "readDefinition.paginationKeys",
                                    ValidationReport.ValidationStage.SOURCE_VALIDATION,
                                    "更换为唯一组合 / 在源表补唯一索引 / 改用 REPLACE_ALL 全量重导"));
                        }
                    } catch (Exception ex) {
                        issues.add(error("DATABASE_CONNECTION_FAILED",
                                "分页 Key 唯一性校验失败: " + safeMessage(ex),
                                "readDefinition.paginationKeys",
                                ValidationReport.ValidationStage.SOURCE_VALIDATION,
                                "检查 Source 连接与分页键配置后重试"));
                    }
                }
            } else if ("SQL".equals(task.readMode()) && definition instanceof SqlReadDefinition sqlDefinition) {
                SqlSafetyValidator.ValidationResult validation = sqlSafetyValidator.validate(sqlDefinition.rawSql());
                if (!validation.valid()) {
                    issues.add(error(validation.errorCode(), validation.message(),
                            "readDefinition.rawSql", ValidationReport.ValidationStage.SOURCE_VALIDATION,
                            "移除写操作和多语句，仅保留单条 SELECT"));
                } else {
                    SqlMetadataInspector.InspectionResult inspection = sqlInspector.inspect(connection, sqlDefinition.rawSql());
                    try {
                        sqlCompiler.compile(sqlDefinition, inspection.columns());
                    } catch (IllegalArgumentException ex) {
                        issues.add(error("VALIDATION_FAILED", ex.getMessage(),
                                "readDefinition", ValidationReport.ValidationStage.SOURCE_VALIDATION,
                                "根据提示修正读取定义"));
                    }
                }
            } else {
                issues.add(error("VALIDATION_FAILED", "读取定义与模式不匹配",
                        "readDefinition", ValidationReport.ValidationStage.SOURCE_VALIDATION,
                        "重新选择读取模式并填写对应的读取定义"));
            }
        } catch (Exception ex) {
            issues.add(error("DATABASE_CONNECTION_FAILED", "Source 校验失败: " + safeMessage(ex),
                    "source", ValidationReport.ValidationStage.SOURCE_CONFIGURATION,
                    "检查 Source 连接配置后重试"));
        }
    }

    private void validateTargetStructure(TaskRecord task, DatabaseConfig targetConfig,
                                         List<ValidationReport.Issue> issues) {
        if (!DatabaseConfigService.SELF_SINK_ENDPOINT_ID.equals(targetConfig.endpointId())) {
            validateRemoteTargetStructure(task, targetConfig, issues);
            return;
        }
        try (Connection connection = connectionFactory.open(targetConfig)) {
            TargetDatabaseAdapter adapter = adapterFactory.targetAdapter(targetConfig.databaseType());
            TableMetadata targetMetadata = adapter.readTableMetadata(connection, task.targetSchema(), task.targetTable());
            List<String> sourceColumns = sourceColumnNames(task);
            validateMappings(sourceColumns, targetMetadata, task.fieldMappings(), issues);
            // UPSERT 唯一约束
            if (task.writeMode() == WriteMode.UPSERT
                    || task.writeMode() == WriteMode.UPSERT_NO_OVERWRITE) {
                if (task.uniqueKeys().isEmpty()) {
                    issues.add(error("TARGET_UNIQUE_CONSTRAINT_MISSING",
                            "UPSERT / UPSERT_NO_OVERWRITE 必须配置唯一 Key",
                            "uniqueKeys", ValidationReport.ValidationStage.TARGET_VALIDATION,
                            "为写入模式配置唯一 Key"));
                } else if (!adapter.hasUniqueConstraint(targetMetadata, task.uniqueKeys())) {
                    issues.add(error("TARGET_UNIQUE_CONSTRAINT_MISSING",
                            "目标表不存在与唯一 Key 匹配的唯一约束",
                            "uniqueKeys", ValidationReport.ValidationStage.TARGET_VALIDATION,
                            "在目标表上建立匹配的唯一约束"));
                }
            }
            // 回执表 READY
            if (!adapter.receiptTableExists(connection)) {
                issues.add(error("SINK_NOT_READY",
                        "目标库缺少回执表 mic_sync_batch_receipt，需由 DBA 初始化后重试",
                        "receipt", ValidationReport.ValidationStage.SINK_HANDSHAKE,
                        "由 DBA 执行回执表初始化 SQL 后重试"));
            }
        } catch (Exception ex) {
            issues.add(error("DATABASE_CONNECTION_FAILED", "Target 校验失败: " + safeMessage(ex),
                    "sink", ValidationReport.ValidationStage.TARGET_CONFIGURATION,
                    "检查 Sink 连接配置与目标表后重试"));
        }
    }

    private void validateRemoteTargetStructure(TaskRecord task, DatabaseConfig targetConfig,
                                               List<ValidationReport.Issue> issues) {
        EndpointRecord endpoint = endpointService.get(targetConfig.endpointId()).orElse(null);
        if (endpoint == null) {
            issues.add(error("VALIDATION_FAILED", "目标数据源所属端不存在",
                    "sinkEndpointId", ValidationReport.ValidationStage.TARGET_CONFIGURATION,
                    "重新选择 Sink 端"));
            return;
        }
        try {
            AgentProtocol.TargetPreflightResponse response = agentClient.validateTarget(endpoint,
                    new AgentProtocol.TargetPreflightRequest(
                            targetConfig.id(),
                            task.targetSchema(),
                            task.targetTable(),
                            task.writeMode().name(),
                            task.uniqueKeys(),
                            task.fieldMappings(),
                            sourceColumnNames(task)));
            for (AgentProtocol.PreflightIssue remote : response.issues()) {
                issues.add(new ValidationReport.Issue(
                        "BLOCKING".equals(remote.severity())
                                ? ValidationReport.Severity.BLOCKING
                                : ValidationReport.Severity.WARNING,
                        remote.code(),
                        remote.message(),
                        remote.field(),
                        parseStage(remote.stage()),
                        remote.suggestedAction()));
            }
        } catch (IllegalStateException ex) {
            issues.add(error("DATABASE_CONNECTION_FAILED", "远端目标校验失败: " + safeMessage(ex),
                    "sink", ValidationReport.ValidationStage.TARGET_CONFIGURATION,
                    "确认 Sink 端可访问且目标数据源已配置"));
        }
    }

    private ValidationReport.ValidationStage parseStage(String stage) {
        try {
            return ValidationReport.ValidationStage.valueOf(stage);
        } catch (IllegalArgumentException ex) {
            return ValidationReport.ValidationStage.TARGET_VALIDATION;
        }
    }

    private List<String> sourceColumnNames(TaskRecord task) {
        ReadDefinition definition = task.readDefinition();
        if (definition instanceof TableReadDefinition tableDefinition) {
            return tableDefinition.selectedColumns();
        }
        if (definition instanceof SqlReadDefinition sqlDefinition) {
            return sqlDefinition.resultColumns();
        }
        return List.of();
    }

    private boolean isHttpUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private Set<String> lowerSet(List<String> values) {
        Set<String> set = new HashSet<>();
        values.forEach(v -> set.add(v.toLowerCase(Locale.ROOT)));
        return set;
    }

    /** 分页键是否为「主键或唯一索引」完全匹配的约束唯一组合。 */
    private boolean isConstrainedUniqueKey(TableMetadata metadata, List<String> keys) {
        Set<String> normalized = lowerSet(keys);
        if (metadata.primaryKeyColumns() != null
                && matches(metadata.primaryKeyColumns(), normalized)) {
            return true;
        }
        for (List<String> uniqueIndex : metadata.uniqueIndexes()) {
            if (matches(uniqueIndex, normalized)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(List<String> candidates, Set<String> normalized) {
        if (candidates == null || candidates.size() != normalized.size()) {
            return false;
        }
        Set<String> candidateSet = lowerSet(candidates);
        return candidateSet.equals(normalized);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    private static ValidationReport.Issue error(
            String code,
            String message,
            String field,
            ValidationReport.ValidationStage stage,
            String suggestedAction) {
        return new ValidationReport.Issue(
                ValidationReport.Severity.BLOCKING,
                code,
                message,
                field,
                stage,
                suggestedAction);
    }
}
