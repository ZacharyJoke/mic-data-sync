package com.mic.datasync.source;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 行数据规范化：把 JDBC 原始值转换为可 JSON 序列化、跨库稳定的类型。
 *
 * <p>支持：整数、DECIMAL、浮点、字符串/LONGTEXT、Boolean、日期时间与 NULL；
 * 拒绝 BLOB/BYTEA/BINARY/ARRAY/XML/自定义对象（返回错误由调用方阻断任务）。</p>
 */
@Component
public class RowNormalizer {

    /** 明确拒绝的逻辑类型（MVP 不支持同步）。 */
    private static final Set<String> REJECTED_TYPES = Set.of("BINARY", "ARRAY", "XML", "OTHER");

    /**
     * 规范化单个值。
     *
     * @param value       JDBC 原始值（可为 null）
     * @param logicalType 逻辑类型（STRING/INTEGER/DECIMAL/FLOAT/BOOLEAN/DATETIME/DATE/TIME）
     * @throws UnsupportedTypeException 类型不受支持时抛出
     */
    public Object normalize(Object value, String logicalType) {
        if (value == null) {
            return null;
        }
        if (logicalType == null || REJECTED_TYPES.contains(logicalType)) {
            throw new UnsupportedTypeException(logicalType, value.getClass());
        }
        return switch (logicalType) {
            case "INTEGER" -> toLong(value);
            case "DECIMAL" -> toBigDecimal(value);
            case "FLOAT" -> toDouble(value);
            case "STRING" -> toString(value);
            case "BOOLEAN" -> toBoolean(value);
            case "DATETIME" -> toDateTimeString(value);
            case "DATE" -> toDateString(value);
            case "TIME" -> toTimeString(value);
            default -> throw new UnsupportedTypeException(logicalType, value.getClass());
        };
    }

    private Long toLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    private Double toDouble(Object value) {
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.valueOf(value.toString());
    }

    private String toString(Object value) {
        if (value instanceof byte[] bytes) {
            // BLOB 类内容即使标记为 STRING 也拒绝，避免隐式二进制传输
            throw new UnsupportedTypeException("STRING", value.getClass());
        }
        return value.toString();
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return "1".equals(value.toString()) || "true".equalsIgnoreCase(value.toString());
    }

    private String toDateTimeString(Object value) {
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant().toString();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            // 无时区时间：保留本地时间原样输出。toInstant() 会按应用时区转 UTC，
            // 导致时间偏移 8 小时，并把公元 1 年前的占位日期（如 0001-01-01）推入
            // 公元 0 年，超出 Vastbase/openGauss 范围而拒绝写入。
            return timestamp.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof String string) {
            return string;
        }
        throw new UnsupportedTypeException("DATETIME", value.getClass());
    }

    private String toDateString(Object value) {
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        return value.toString();
    }

    private String toTimeString(Object value) {
        if (value instanceof LocalTime time) {
            return time.format(DateTimeFormatter.ISO_LOCAL_TIME);
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME);
        }
        return value.toString();
    }

    /** 不支持的类型（BLOB/BYTEA/BINARY/ARRAY/XML/自定义对象）。 */
    public static class UnsupportedTypeException extends RuntimeException {
        public UnsupportedTypeException(String logicalType, Class<?> valueType) {
            super("不支持的字段类型: logicalType=" + logicalType + ", javaType=" + valueType.getName());
        }
    }
}
