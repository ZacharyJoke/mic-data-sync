package com.mic.datasync.source;

import com.mic.datasync.database.dialect.SourceDialect;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.source.domain.FilterCondition;
import com.mic.datasync.source.domain.ReadPlan;
import com.mic.datasync.source.domain.TableReadDefinition;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Table 模式读取计划编译器。
 *
 * <p>校验字段/分页键合法性，生成只读预览 SQL 与结构指纹；
 * 无稳定唯一分页 Key 的配置不允许生成可执行计划。</p>
 */
@Component
public class TableReadPlanCompiler {

    private final SourceDialect dialect = new SourceDialect() {
    };

    /**
     * 编译 Table 读取定义。
     *
     * @param definition 用户配置
     * @param metadata   源表实时元数据
     * @throws IllegalArgumentException 表/字段不存在、分页键不稳定或过滤字段非法时抛出
     */
    public ReadPlan compile(TableReadDefinition definition, TableMetadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("源表元数据不可用");
        }
        String schema = definition.schema();
        String table = definition.table();
        if (!table.equalsIgnoreCase(metadata.table())) {
            throw new IllegalArgumentException("源表不存在或已变更: " + qualified(schema, table));
        }

        // 读取字段：默认全部字段
        List<String> columns = definition.selectedColumns().isEmpty()
                ? metadata.columns().stream().map(ColumnMetadata::name).toList()
                : definition.selectedColumns();
        validateColumnsExist(metadata, columns, "读取字段");

        // 分页键必须存在且组合唯一（主键或唯一索引）
        List<String> paginationKeys = definition.paginationKeys();
        if (paginationKeys.isEmpty()) {
            throw new IllegalArgumentException("必须配置稳定且唯一的分页 Key");
        }
        validateColumnsExist(metadata, paginationKeys, "分页 Key");
        if (!isStableUniqueKey(metadata, paginationKeys)) {
            throw new IllegalArgumentException("分页 Key 组合必须与主键或唯一索引完全一致");
        }

        // 更新时间字段与过滤字段必须存在
        if (definition.updatedTimeField() != null && !definition.updatedTimeField().isBlank()) {
            validateColumnsExist(metadata, List.of(definition.updatedTimeField()), "更新时间字段");
        }
        for (FilterCondition filter : definition.filters()) {
            validateColumnsExist(metadata, List.of(filter.column()), "过滤字段");
        }

        String previewSql = buildPreviewSql(schema, table, columns, definition.filters());
        String fingerprint = structureFingerprint(table, metadata);
        return new ReadPlan(
                "TABLE", schema, table, columns, definition.filters(),
                paginationKeys, definition.updatedTimeField(), previewSql, fingerprint);
    }

    /** 生成只读预览 SQL（参数占位，LIMIT 20）。 */
    private String buildPreviewSql(String schema, String table, List<String> columns,
                                   List<FilterCondition> filters) {
        String columnList = columns.stream()
                .map(dialect::quoteIdentifier)
                .reduce((a, b) -> a + ", " + b)
                .orElse("*");
        String qualifiedTable = (schema == null || schema.isBlank())
                ? dialect.quoteIdentifier(table)
                : dialect.quoteIdentifier(schema) + "." + dialect.quoteIdentifier(table);
        StringBuilder sql = new StringBuilder("SELECT " + columnList + " FROM " + qualifiedTable);
        if (!filters.isEmpty()) {
            List<String> conditions = new ArrayList<>();
            for (FilterCondition filter : filters) {
                conditions.add(dialect.quoteIdentifier(filter.column()) + " " + filter.operator() + " ?");
            }
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" LIMIT 20");
        return sql.toString();
    }

    /** 结构指纹：表名 + 字段（名称/类型/可空性）的 SHA-256。 */
    private String structureFingerprint(String table, TableMetadata metadata) {
        StringBuilder content = new StringBuilder(table.toLowerCase(Locale.ROOT));
        for (ColumnMetadata column : metadata.columns()) {
            content.append('|').append(column.name().toLowerCase(Locale.ROOT))
                    .append(':').append(column.typeName().toLowerCase(Locale.ROOT))
                    .append(':').append(column.nullable());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private void validateColumnsExist(TableMetadata metadata, List<String> columns, String label) {
        Set<String> names = new HashSet<>();
        metadata.columns().forEach(c -> names.add(c.name().toLowerCase(Locale.ROOT)));
        for (String column : columns) {
            if (!names.contains(column.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(label + "不存在: " + column);
            }
        }
    }

    private boolean isStableUniqueKey(TableMetadata metadata, List<String> keys) {
        Set<String> normalized = normalize(keys);
        if (matches(metadata.primaryKeyColumns(), normalized)) {
            return true;
        }
        for (List<String> uniqueIndex : metadata.uniqueIndexes()) {
            if (matches(uniqueIndex, normalized)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> normalize(List<String> columns) {
        Set<String> set = new HashSet<>();
        columns.forEach(c -> set.add(c.toLowerCase(Locale.ROOT)));
        return set;
    }

    private boolean matches(List<String> candidates, Set<String> normalized) {
        if (candidates == null || candidates.size() != normalized.size()) {
            return false;
        }
        Set<String> candidateSet = normalize(candidates);
        return candidateSet.equals(normalized);
    }

    private String qualified(String schema, String table) {
        return (schema == null || schema.isBlank()) ? table : schema + "." + table;
    }
}
