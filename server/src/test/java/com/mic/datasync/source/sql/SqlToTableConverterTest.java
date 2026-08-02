package com.mic.datasync.source.sql;

import com.mic.datasync.source.domain.FilterCondition;
import com.mic.datasync.source.domain.TableReadDefinition;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL→Table 尽力转换测试。
 */
class SqlToTableConverterTest {

    private final SqlToTableConverter converter = new SqlToTableConverter();

    @Test
    void convertsSimpleSelectWithWhereAndOrderBy() {
        Optional<TableReadDefinition> result = converter.tryConvert(
                "SELECT id, name FROM patient WHERE status = 'ACTIVE' ORDER BY id");

        assertThat(result).isPresent();
        TableReadDefinition definition = result.get();
        assertThat(definition.schema()).isNull();
        assertThat(definition.table()).isEqualTo("patient");
        assertThat(definition.selectedColumns()).containsExactly("id", "name");
        assertThat(definition.filters()).containsExactly(new FilterCondition("status", "=", "ACTIVE"));
        assertThat(definition.paginationKeys()).containsExactly("id");
    }

    @Test
    void convertsSelectWithSchemaAndAlias() {
        Optional<TableReadDefinition> result = converter.tryConvert(
                "SELECT id, name AS patient_name FROM public.patient WHERE del_flag = 0");

        assertThat(result).isPresent();
        TableReadDefinition definition = result.get();
        assertThat(definition.schema()).isEqualTo("public");
        assertThat(definition.table()).isEqualTo("patient");
        assertThat(definition.selectedColumns()).containsExactly("id", "patient_name");
    }

    @Test
    void convertsAndConditionsIntoMultipleFilters() {
        Optional<TableReadDefinition> result = converter.tryConvert(
                "SELECT id FROM patient WHERE status = 'A' AND del_flag = 0");

        assertThat(result).isPresent();
        assertThat(result.get().filters()).containsExactly(
                new FilterCondition("status", "=", "A"),
                new FilterCondition("del_flag", "=", 0L));
    }

    @Test
    void selectStarIsNotReliablyConvertible() {
        assertThat(converter.tryConvert("SELECT * FROM patient")).isEmpty();
    }

    @Test
    void joinIsNotConvertible() {
        assertThat(converter.tryConvert(
                "SELECT a.id FROM patient a JOIN orders o ON a.id = o.patient_id")).isEmpty();
    }

    @Test
    void functionInSelectIsNotConvertible() {
        assertThat(converter.tryConvert("SELECT LOWER(name) FROM patient")).isEmpty();
    }

    @Test
    void complexWhereIsNotConvertible() {
        assertThat(converter.tryConvert("SELECT id FROM patient WHERE status IN ('A', 'B')")).isEmpty();
    }

    @Test
    void dmlIsNotConvertible() {
        assertThat(converter.tryConvert("DELETE FROM patient")).isEmpty();
    }
}
