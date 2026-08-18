package com.mic.datasync.source.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 增量策略字段的序列化兼容测试：
 * 旧任务 JSON 缺省时回退默认值，新配置正确往返。
 */
class IncrementalStrategySerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void legacyJsonDefaultsToTimeWindowAnd10MinutesLookback() throws Exception {
        String legacy = """
                {"schema":"public","table":"patient","selectedColumns":[],
                 "filters":[],"paginationKeys":["id"],"updatedTimeField":"updated_at"}
                """;

        TableReadDefinition definition = objectMapper.readValue(legacy, TableReadDefinition.class);

        assertThat(definition.incrementalStrategy()).isEqualTo(IncrementalStrategy.TIME_WINDOW);
        assertThat(definition.incrementalLookbackMinutes()).isEqualTo(10);
    }

    @Test
    void dualPhaseConfigurationRoundTrips() throws Exception {
        TableReadDefinition definition = new TableReadDefinition(
                "public", "patient", java.util.List.of("id"), java.util.List.of(),
                java.util.List.of("id"), "updated_at",
                IncrementalStrategy.DUAL_PHASE, 1440);

        String json = objectMapper.writeValueAsString(definition);
        TableReadDefinition restored = objectMapper.readValue(json, TableReadDefinition.class);

        assertThat(restored.incrementalStrategy()).isEqualTo(IncrementalStrategy.DUAL_PHASE);
        assertThat(restored.incrementalLookbackMinutes()).isEqualTo(1440);
    }

    @Test
    void lookbackMustBePositive() {
        assertThatThrownBy(() -> new TableReadDefinition(
                "public", "patient", java.util.List.of("id"), java.util.List.of(),
                java.util.List.of("id"), "updated_at",
                IncrementalStrategy.DUAL_PHASE, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("回看分钟数");
    }
}
