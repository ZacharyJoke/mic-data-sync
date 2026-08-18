package com.mic.datasync.source;

import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.source.domain.FilterCondition;
import com.mic.datasync.source.domain.ReadPlan;
import com.mic.datasync.source.domain.ReadPlan.PaginationStrategy;
import com.mic.datasync.source.domain.TableReadDefinition;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table 读取计划编译测试。
 */
class TableReadPlanCompilerTest {

    private final TableReadPlanCompiler compiler = new TableReadPlanCompiler();

    private static final TableMetadata PATIENT = new TableMetadata(
            "public", "patient",
            List.of(
                    new ColumnMetadata("id", Types.BIGINT, "bigint", 0, false, true),
                    new ColumnMetadata("name", Types.VARCHAR, "varchar", 64, true, false),
                    new ColumnMetadata("updated_time", Types.TIMESTAMP, "timestamp", 0, true, false)),
            List.of("id"),
            List.of());

    @Test
    void compilesWithPrimaryKeyAsPaginationKey() {
        TableReadDefinition definition = new TableReadDefinition(
                "public", "patient", List.of("id", "name"),
                List.of(new FilterCondition("name", "!=", "test")),
                List.of("id"), "updated_time");

        ReadPlan plan = compiler.compile(definition, PATIENT);

        assertThat(plan.mode()).isEqualTo("TABLE");
        assertThat(plan.columns()).containsExactly("id", "name");
        assertThat(plan.paginationKeys()).containsExactly("id");
        assertThat(plan.previewSql())
                .startsWith("SELECT \"id\", \"name\" FROM \"public\".\"patient\"")
                .contains("WHERE \"name\" != ?")
                .endsWith("LIMIT 20");
        assertThat(plan.structureFingerprint()).isNotBlank();
    }

    @Test
    void emptyPaginationKeysIsRejected() {
        TableReadDefinition definition = new TableReadDefinition(
                "public", "patient", List.of("id"), List.of(),
                List.of(), "updated_time");

        assertThatThrownBy(() -> compiler.compile(definition, PATIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("分页 Key");
    }

    @Test
    void unstablePaginationKeyIsRejected() {
        TableReadDefinition definition = new TableReadDefinition(
                "public", "patient", List.of("id"), List.of(),
                List.of("name"), null);

        assertThatThrownBy(() -> compiler.compile(definition, PATIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主键或唯一索引");
    }

    @Test
    void unknownColumnIsRejected() {
        TableReadDefinition definition = new TableReadDefinition(
                "public", "patient", List.of("id", "not_exist"), List.of(),
                List.of("id"), null);

        assertThatThrownBy(() -> compiler.compile(definition, PATIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void emptyColumnsFallsBackToAllMetadataColumns() {
        TableReadDefinition definition = new TableReadDefinition(
                "public", "patient", List.of(), List.of(),
                List.of("id"), null);

        ReadPlan plan = compiler.compile(definition, PATIENT);
        assertThat(plan.columns()).containsExactly("id", "name", "updated_time");
    }

    @Test
    void offsetStrategyAllowsEmptyOrNonUniquePaginationKeys() {
        // REPLACE_ALL：分页键可为空
        TableReadDefinition emptyKeys = new TableReadDefinition(
                "public", "patient", List.of("id", "name"), List.of(),
                List.of(), null);
        ReadPlan plan = compiler.compile(emptyKeys, PATIENT, PaginationStrategy.OFFSET);

        assertThat(plan.pagination()).isEqualTo(PaginationStrategy.OFFSET);
        assertThat(plan.paginationKeys()).isEmpty();

        // REPLACE_ALL：非唯一组合也放行
        TableReadDefinition nonUniqueKeys = new TableReadDefinition(
                "public", "patient", List.of("id", "name"), List.of(),
                List.of("name"), null);
        assertThat(compiler.compile(nonUniqueKeys, PATIENT, PaginationStrategy.OFFSET).paginationKeys())
                .containsExactly("name");
    }

    @Test
    void softUniqueAcceptedAllowsNonConstrainedPaginationKey() {
        TableReadDefinition definition = new TableReadDefinition(
                "public", "patient", List.of("id", "name"), List.of(),
                List.of("name"), null);

        ReadPlan plan = compiler.compile(definition, PATIENT, PaginationStrategy.KEYSET, true);

        assertThat(plan.paginationKeys()).containsExactly("name");
    }
}
