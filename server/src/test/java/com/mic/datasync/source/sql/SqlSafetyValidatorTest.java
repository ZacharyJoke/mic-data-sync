package com.mic.datasync.source.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 安全校验测试：合法单表查询接受，危险结构拒绝。
 */
class SqlSafetyValidatorTest {

    private final SqlSafetyValidator validator = new SqlSafetyValidator();

    @Test
    void acceptsSimpleSelectStar() {
        assertValid("SELECT * FROM patient");
    }

    @Test
    void acceptsAliasAndColumns() {
        assertValid("SELECT id, name AS patient_name FROM patient");
    }

    @Test
    void acceptsCaseExpression() {
        assertValid("SELECT CASE WHEN status = 'A' THEN 1 ELSE 0 END AS flag FROM patient");
    }

    @Test
    void acceptsCastExpression() {
        assertValid("SELECT CAST(id AS VARCHAR) FROM patient");
    }

    @Test
    void acceptsWhitelistFunction() {
        assertValid("SELECT COALESCE(name, ''), LOWER(name) FROM patient");
    }

    @Test
    void acceptsWhereWithAndOrInBetween() {
        assertValid("SELECT id FROM patient WHERE status = 'ACTIVE' AND del_flag = 0");
        assertValid("SELECT id FROM patient WHERE id IN (1, 2, 3)");
        assertValid("SELECT id FROM patient WHERE updated_time BETWEEN '2026-01-01' AND '2026-01-02'");
        assertValid("SELECT id FROM patient WHERE name IS NOT NULL");
    }

    @Test
    void acceptsOrderBy() {
        assertValid("SELECT id FROM patient ORDER BY updated_time DESC, id");
    }

    @Test
    void rejectsDmlStatements() {
        assertInvalid("INSERT INTO patient (id) VALUES (1)", "SQL_NOT_SINGLE_SELECT");
        assertInvalid("UPDATE patient SET status = 'A'", "SQL_NOT_SINGLE_SELECT");
        assertInvalid("DELETE FROM patient", "SQL_NOT_SINGLE_SELECT");
    }

    @Test
    void rejectsDdlStatements() {
        assertInvalid("CREATE TABLE t (id INT)", "SQL_NOT_SINGLE_SELECT");
        assertInvalid("DROP TABLE patient", "SQL_NOT_SINGLE_SELECT");
    }

    @Test
    void rejectsUnionAndSetOperations() {
        assertInvalid("SELECT id FROM a UNION SELECT id FROM b", "SQL_NOT_SINGLE_SELECT");
    }

    @Test
    void rejectsJoin() {
        assertInvalid("SELECT a.id FROM patient a JOIN orders o ON a.id = o.patient_id", "SQL_UNSUPPORTED_STRUCTURE");
    }

    @Test
    void rejectsFromSubquery() {
        assertInvalid("SELECT id FROM (SELECT id FROM patient) sub", "SQL_UNSUPPORTED_STRUCTURE");
    }

    @Test
    void rejectsLimit() {
        assertInvalid("SELECT id FROM patient LIMIT 10", "SQL_UNSUPPORTED_STRUCTURE");
    }

    @Test
    void rejectsCte() {
        assertInvalid("WITH c AS (SELECT id FROM patient) SELECT id FROM c", "SQL_UNSUPPORTED_STRUCTURE");
    }

    @Test
    void rejectsSideEffectFunction() {
        assertInvalid("SELECT nextval('seq') FROM patient", "SQL_UNSUPPORTED_STRUCTURE");
    }

    @Test
    void rejectsForUpdateLock() {
        assertInvalid("SELECT id FROM patient FOR UPDATE", "SQL_UNSUPPORTED_STRUCTURE");
    }

    @Test
    void rejectsUnparseableSql() {
        assertInvalid("SELECT FROM WHERE", "VALIDATION_FAILED");
    }

    private void assertValid(String sql) {
        SqlSafetyValidator.ValidationResult result = validator.validate(sql);
        assertThat(result.valid())
                .as("期望合法: %s", sql)
                .isTrue();
    }

    private void assertInvalid(String sql, String expectedErrorCode) {
        SqlSafetyValidator.ValidationResult result = validator.validate(sql);
        assertThat(result.valid())
                .as("期望非法: %s", sql)
                .isFalse();
        assertThat(result.errorCode())
                .as("SQL: %s", sql)
                .isEqualTo(expectedErrorCode);
    }
}
