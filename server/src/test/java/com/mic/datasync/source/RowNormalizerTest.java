package com.mic.datasync.source;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 行数据规范化测试。
 */
class RowNormalizerTest {

    private final RowNormalizer normalizer = new RowNormalizer();

    @Test
    void nullIsPreserved() {
        assertThat(normalizer.normalize(null, "STRING")).isNull();
    }

    @Test
    void integerIsNormalizedToLong() {
        assertThat(normalizer.normalize(42, "INTEGER")).isEqualTo(42L);
        assertThat(normalizer.normalize(1_000_000_000_000L, "INTEGER")).isEqualTo(1_000_000_000_000L);
    }

    @Test
    void decimalIsPreservedAsBigDecimal() {
        assertThat(normalizer.normalize(new BigDecimal("123.45"), "DECIMAL")).isEqualTo(new BigDecimal("123.45"));
    }

    @Test
    void floatIsNormalizedToDouble() {
        Double result = (Double) normalizer.normalize(3.14f, "FLOAT");
        assertThat(result).isCloseTo(3.14d, org.assertj.core.data.Offset.offset(0.0001d));
    }

    @Test
    void stringAndLongTextArePreserved() {
        assertThat(normalizer.normalize("你好", "STRING")).isEqualTo("你好");
        assertThat(normalizer.normalize("x".repeat(100_000), "STRING"))
                .isEqualTo("x".repeat(100_000));
    }

    @Test
    void booleanIsNormalized() {
        assertThat(normalizer.normalize(true, "BOOLEAN")).isEqualTo(true);
    }

    @Test
    void dateTimeIsNormalizedToIsoString() {
        Instant instant = Instant.parse("2026-01-01T08:00:00Z");
        assertThat(normalizer.normalize(instant, "DATETIME")).isEqualTo("2026-01-01T08:00:00Z");
    }

    @Test
    void localDateTimeIsPreservedWithoutTimeZoneOffset() {
        LocalDateTime local = LocalDateTime.of(2026, 4, 14, 6, 8, 1, 911_283_000);
        assertThat(normalizer.normalize(local, "DATETIME")).isEqualTo("2026-04-14T06:08:01.911283");
    }

    @Test
    void sqlTimestampIsPreservedWithoutUtcShift() {
        java.sql.Timestamp timestamp = java.sql.Timestamp.valueOf("2026-04-14 06:08:01.911283");
        assertThat(normalizer.normalize(timestamp, "DATETIME")).isEqualTo("2026-04-14T06:08:01.911283");
    }

    @Test
    void earlyPlaceholderDateStaysWithinValidRange() {
        // 占位日期 0001-01-01 00:00:00 若按 UTC 转换会变成 0000-12-29T16:00:00Z，
        // 超出 Vastbase/openGauss 范围；修复后应保留本地时间原样输出。
        java.sql.Timestamp placeholder = java.sql.Timestamp.valueOf("0001-01-01 00:00:00.000");
        assertThat(normalizer.normalize(placeholder, "DATETIME"))
                .isEqualTo("0001-01-01T00:00:00");
    }

    @Test
    void dateIsNormalizedToIsoString() {
        assertThat(normalizer.normalize(LocalDate.of(2026, 1, 1), "DATE")).isEqualTo("2026-01-01");
    }

    @Test
    void binaryTypeIsRejected() {
        assertThatThrownBy(() -> normalizer.normalize(new byte[]{1, 2, 3}, "BINARY"))
                .isInstanceOf(RowNormalizer.UnsupportedTypeException.class)
                .hasMessageContaining("BINARY");
    }

    @Test
    void blobBytesDisguisedAsStringAreRejected() {
        assertThatThrownBy(() -> normalizer.normalize(new byte[]{1, 2}, "STRING"))
                .isInstanceOf(RowNormalizer.UnsupportedTypeException.class);
    }

    @Test
    void arrayAndOtherTypesAreRejected() {
        assertThatThrownBy(() -> normalizer.normalize(new Object[]{1}, "ARRAY"))
                .isInstanceOf(RowNormalizer.UnsupportedTypeException.class);
        assertThatThrownBy(() -> normalizer.normalize(new Object(), "OTHER"))
                .isInstanceOf(RowNormalizer.UnsupportedTypeException.class);
    }
}
