package com.mic.datasync.source.sql;

import com.mic.datasync.source.domain.ReadPlan;
import com.mic.datasync.source.domain.SqlReadDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SQL 模式读取计划编译器。
 *
 * <p>SQL 结果字段以 columnLabel 为名；重复列名禁止启用。
 * 预览 SQL 为原始查询（运行时外层包装增量、Keyset、排序与批次限制）。</p>
 */
@Component
public class SqlReadPlanCompiler {

    /**
     * 编译 SQL 读取定义。
     *
     * @param definition    用户配置（rawSql/baseTable 等）
     * @param resultColumns 字段探查结果
     * @throws IllegalArgumentException 结果存在重复列名时抛出
     */
    public ReadPlan compile(SqlReadDefinition definition, List<SqlMetadataInspector.ResultColumn> resultColumns) {
        if (resultColumns == null || resultColumns.isEmpty()) {
            throw new IllegalArgumentException("SQL 结果字段为空，无法生成读取计划");
        }
        List<String> columnNames = resultColumns.stream()
                .map(SqlMetadataInspector.ResultColumn::name)
                .toList();
        // 重复列名禁止启用
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String name : columnNames) {
            if (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("SQL 结果存在重复列名，禁止启用: " + name);
            }
        }
        return new ReadPlan(
                "SQL",
                definition.baseTable() == null ? null : schemaOf(definition.baseTable()),
                tableOf(definition.baseTable()),
                columnNames,
                List.of(),
                definition.paginationKeys(),
                definition.updatedTimeField(),
                definition.rawSql(),
                structureFingerprint(columnNames));
    }

    private String schemaOf(String qualified) {
        int dot = qualified.indexOf('.');
        return dot > 0 ? qualified.substring(0, dot) : null;
    }

    private String tableOf(String qualified) {
        int dot = qualified.indexOf('.');
        return dot > 0 ? qualified.substring(dot + 1) : qualified;
    }

    /** 结果列名顺序指纹（与 Inspector 的指纹口径一致，这里按编译产物简化）。 */
    private String structureFingerprint(List<String> columnNames) {
        StringBuilder content = new StringBuilder();
        columnNames.forEach(c -> content.append(c.toLowerCase(java.util.Locale.ROOT)).append('|'));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
