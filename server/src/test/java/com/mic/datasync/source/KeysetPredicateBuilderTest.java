package com.mic.datasync.source;

import com.mic.datasync.database.dialect.SourceDialect;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Keyset 谓词生成器测试：单 Key、组合 Key、同时间戳多行、NULL 拒绝。
 */
class KeysetPredicateBuilderTest {

    private static final SourceDialect DIALECT = new SourceDialect() {
    };

    @Test
    void firstPageWithoutCursorProducesNullPredicate() {
        assertThat(KeysetPredicateBuilder.buildPredicate(
                List.of("id"), Map.of(), DIALECT)).isNull();
        assertThat(KeysetPredicateBuilder.buildPredicate(
                List.of("id"), null, DIALECT)).isNull();
    }

    @Test
    void singleKeyProducesSimpleGreaterThan() {
        KeysetPredicateBuilder.KeysetPredicate predicate =
                KeysetPredicateBuilder.buildPredicate(List.of("id"), Map.of("id", 100L), DIALECT);

        assertThat(predicate).isNotNull();
        assertThat(predicate.sql()).isEqualTo("\"id\" > ?");
        assertThat(predicate.parameters()).containsExactly(100L);
    }

    @Test
    void compositeKeyProducesOrChainWithOrderedParameters() {
        KeysetPredicateBuilder.KeysetPredicate predicate = KeysetPredicateBuilder.buildPredicate(
                List.of("updated_time", "id"),
                Map.of("updated_time", "2026-01-01 10:00:00", "id", 42L),
                DIALECT);

        assertThat(predicate.sql()).isEqualTo(
                "(\"updated_time\" > ?) OR (\"updated_time\" = ? AND \"id\" > ?)");
        assertThat(predicate.parameters()).containsExactly(
                "2026-01-01 10:00:00", "2026-01-01 10:00:00", 42L);
    }

    @Test
    void sameTimestampMultipleRowsIsHandledByCompositeCursor() {
        // 同时间戳场景：updated_time 相同，用 id 打破平局
        KeysetPredicateBuilder.KeysetPredicate predicate = KeysetPredicateBuilder.buildPredicate(
                List.of("updated_time", "id"),
                Map.of("updated_time", "2026-01-01 10:00:00", "id", 100L),
                DIALECT);

        // 第二批（同时间戳、id 更大）与第三批（时间更大）都稳定
        assertThat(predicate.sql()).contains("AND \"id\" > ?");

        KeysetPredicateBuilder.KeysetPredicate next = KeysetPredicateBuilder.buildPredicate(
                List.of("updated_time", "id"),
                Map.of("updated_time", "2026-01-01 10:00:00", "id", 200L),
                DIALECT);
        assertThat(next.sql()).isEqualTo(predicate.sql());
        assertThat(next.parameters()).containsExactly("2026-01-01 10:00:00", "2026-01-01 10:00:00", 200L);
    }

    @Test
    void nullCursorValueIsRejected() {
        Map<String, Object> cursor = new HashMap<>();
        cursor.put("id", null);
        assertThatThrownBy(() -> KeysetPredicateBuilder.buildPredicate(
                List.of("id"), cursor, DIALECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许 NULL");
    }

    @Test
    void missingCursorKeyIsRejected() {
        assertThatThrownBy(() -> KeysetPredicateBuilder.buildPredicate(
                List.of("id", "updated_time"), Map.of("id", 1L), DIALECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许 NULL");
    }

    @Test
    void emptyKeysAreRejected() {
        assertThatThrownBy(() -> KeysetPredicateBuilder.buildPredicate(
                List.of(), Map.of("id", 1L), DIALECT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifiersAreQuotedWithDialect() {
        KeysetPredicateBuilder.KeysetPredicate predicate = KeysetPredicateBuilder.buildPredicate(
                List.of("order by"), Map.of("order by", "x"), DIALECT);
        assertThat(predicate.sql()).isEqualTo("\"order by\" > ?");
    }
}
