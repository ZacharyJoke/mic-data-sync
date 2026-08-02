package com.mic.datasync.webapi;

import com.mic.datasync.database.ConnectionFactory;
import com.mic.datasync.database.DatabaseConfig;
import com.mic.datasync.database.DatabaseConfigService;
import com.mic.datasync.database.DatabaseRole;
import com.mic.datasync.shared.error.ErrorCode;
import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.source.sql.SqlMetadataInspector;
import com.mic.datasync.source.sql.SqlSafetyValidator;
import com.mic.datasync.source.sql.SqlToTableConverter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL 模式探查接口（需管理员登录，依赖 Source 数据库配置）。
 */
@RestController
@RequestMapping("/api/v1/source/sql")
public class SourceSqlController {

    private final DatabaseConfigService configService;
    private final ConnectionFactory connectionFactory;
    private final SqlSafetyValidator safetyValidator;
    private final SqlMetadataInspector metadataInspector;
    private final SqlToTableConverter sqlToTableConverter;

    public SourceSqlController(DatabaseConfigService configService,
                               ConnectionFactory connectionFactory,
                               SqlSafetyValidator safetyValidator,
                               SqlMetadataInspector metadataInspector,
                               SqlToTableConverter sqlToTableConverter) {
        this.configService = configService;
        this.connectionFactory = connectionFactory;
        this.safetyValidator = safetyValidator;
        this.metadataInspector = metadataInspector;
        this.sqlToTableConverter = sqlToTableConverter;
    }

    /** 校验 + 字段探查 + 尽力转换为 Table。 */
    @PostMapping("/inspect")
    public ResponseEntity<?> inspect(@RequestBody InspectRequest request,
                                     @RequestParam(required = false) String dataSourceId) {
        String sql = request.sql();
        SqlSafetyValidator.ValidationResult validation = safetyValidator.validate(sql);
        if (!validation.valid()) {
            // 无法解析/不合法的 SQL：只返回校验结果，可保存草稿但不能启用
            return ResponseEntity.ok(new InspectResponse(
                    false, validation.errorCode(), validation.message(),
                    null, null, null, null));
        }

        DatabaseConfig config = resolveConfig(dataSourceId);
        if (config == null) {
            return ResponseEntity.badRequest().body(new ApiError(
                    ErrorCode.VALIDATION_FAILED.name(), "未配置或找不到 Source 数据源",
                    UUID.randomUUID().toString(), Map.of()));
        }
        try (Connection connection = connectionFactory.open(config)) {
            SqlMetadataInspector.InspectionResult inspection = metadataInspector.inspect(connection, sql);
            Optional<TableReadDefinition> conversion = sqlToTableConverter.tryConvert(sql);
            return ResponseEntity.ok(new InspectResponse(
                    true, null, null,
                    inspection.columns().stream()
                            .map(c -> new ResultColumnDto(c.name(), c.typeName(), c.logicalType(), c.nullable()))
                            .toList(),
                    inspection.duplicateNames(),
                    inspection.structureFingerprint(),
                    conversion.map(c -> new TableConversionDto(true, c.schema(), c.table(),
                            c.selectedColumns(), c.filters(), c.paginationKeys()))
                            .orElse(new TableConversionDto(false, null, null, null, null, null))));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                    ErrorCode.DATABASE_CONNECTION_FAILED.name(),
                    "SQL 字段探查失败: " + safeMessage(ex),
                    UUID.randomUUID().toString(), Map.of()));
        }
    }

    private DatabaseConfig resolveConfig(String dataSourceId) {
        if (dataSourceId != null && !dataSourceId.isBlank()) {
            return configService.get(dataSourceId)
                    .filter(config -> config.role() == DatabaseRole.SOURCE)
                    .orElse(null);
        }
        return configService.getDefault(DatabaseRole.SOURCE).orElse(null);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null ? ex.getClass().getSimpleName() : message;
    }

    /** 请求体。 */
    public record InspectRequest(String sql) {
    }

    /** 探查响应。 */
    public record InspectResponse(
            boolean valid,
            String errorCode,
            String message,
            List<ResultColumnDto> resultColumns,
            List<String> duplicateNames,
            String structureFingerprint,
            TableConversionDto tableConversion) {
    }

    /** 结果字段 DTO。 */
    public record ResultColumnDto(String name, String typeName, String logicalType, boolean nullable) {
    }

    /** SQL→Table 转换结果。 */
    public record TableConversionDto(
            boolean success,
            String schema,
            String table,
            List<String> selectedColumns,
            List<com.mic.datasync.source.domain.FilterCondition> filters,
            List<String> paginationKeys) {
    }
}
