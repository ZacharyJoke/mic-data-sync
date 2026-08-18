package com.mic.datasync.task;

import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import com.mic.datasync.source.domain.TableReadDefinition;
import com.mic.datasync.task.TaskValidator.ValidationReport;
import com.mic.datasync.task.TaskValidator.ValidationReport.Issue;
import com.mic.datasync.task.TaskValidator.ValidationReport.Severity;
import com.mic.datasync.task.TaskValidator.ValidationReport.ValidationStage;
import com.mic.datasync.task.domain.TaskDefinition.WriteMode;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务校验器纯逻辑测试（无真实数据库连接）。
 */
class TaskValidatorTest {

    private final TaskValidator validator =
            new TaskValidator(null, null, null, null, null, null, null, null, null);

    @Test
    void supportedDirectionAndValidInputsPass() {
        ValidationReport report = validator.validateBasics(
                "https://sink.example:19090", DatabaseType.KINGBASE_ES, DatabaseType.OPEN_GAUSS, true);
        assertThat(report.valid()).isTrue();
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void kingbaseToKingbaseDirectionIsBlocked() {
        ValidationReport report = validator.validateBasics(
                "http://sink:19090", DatabaseType.KINGBASE_ES, DatabaseType.KINGBASE_ES, true);
        assertThat(report.valid()).isFalse();
        assertThat(report.issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("UNSUPPORTED_DATABASE_DIRECTION");
        });
    }

    @Test
    void invalidSinkUrlIsBlocked() {
        ValidationReport report = validator.validateBasics(
                "ftp://sink", DatabaseType.OPEN_GAUSS, DatabaseType.OPEN_GAUSS, true);
        assertThat(report.valid()).isFalse();
        assertThat(report.issues()).anySatisfy(issue ->
                assertThat(issue.field()).isEqualTo("remoteSinkUrl"));
    }

    @Test
    void missingSinkInstanceIsBlocked() {
        ValidationReport report = validator.validateBasics(
                "http://sink:19090", DatabaseType.OPEN_GAUSS, DatabaseType.OPEN_GAUSS, false);
        assertThat(report.valid()).isFalse();
        assertThat(report.issues()).anySatisfy(issue ->
                assertThat(issue.field()).isEqualTo("expectedSinkInstanceId"));
    }

    @Test
    void issuesExposeSeverityStageAndSuggestedAction() {
        ValidationReport report = validator.validateBasics(
                "ftp://sink", DatabaseType.OPEN_GAUSS, DatabaseType.OPEN_GAUSS, true);

        assertThat(report.issues()).anySatisfy(issue -> {
            assertThat(issue.severity()).isEqualTo(Severity.BLOCKING);
            assertThat(issue.stage()).isEqualTo(ValidationStage.SINK_HANDSHAKE);
            assertThat(issue.suggestedAction()).isNotBlank();
        });
    }

    @Test
    void targetColumnIssueUsesFieldPathAndStage() {
        TableMetadata target = new TableMetadata(
                "public", "patient",
                List.of(new ColumnMetadata("id", Types.BIGINT, "bigint", 0, false, true)),
                List.of("id"),
                List.of());

        List<ValidationReport.Issue> issues = new ArrayList<>();
        validator.validateMappings(List.of("patient_id"), target,
                List.of(new FieldMapping("patient_id", "patient_id")), issues);

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.severity()).isEqualTo(Severity.BLOCKING);
            assertThat(issue.field()).isEqualTo("fieldMappings.patient_id");
            assertThat(issue.stage()).isEqualTo(ValidationStage.TARGET_VALIDATION);
            assertThat(issue.suggestedAction()).isNotBlank();
        });
    }

    @Test
    void requiredTargetColumnWithoutMappingIsBlocked() {
        TableMetadata target = new TableMetadata(
                "public", "patient",
                List.of(
                        new ColumnMetadata("id", Types.BIGINT, "bigint", 0, false, true),
                        new ColumnMetadata("name", Types.VARCHAR, "varchar", 64, false, false)),
                List.of("id"),
                List.of());

        List<ValidationReport.Issue> issues = new ArrayList<>();
        validator.validateMappings(List.of("id"), target, List.of(), issues);

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.blocking()).isTrue();
            assertThat(issue.message()).contains("name");
        });
    }

    @Test
    void unknownSourceOrTargetFieldIsBlocked() {
        TableMetadata target = new TableMetadata(
                "public", "patient",
                List.of(new ColumnMetadata("id", Types.BIGINT, "bigint", 0, false, true)),
                List.of("id"),
                List.of());

        List<ValidationReport.Issue> issues = new ArrayList<>();
        validator.validateMappings(List.of("id"), target,
                List.of(new FieldMapping("not_exist", "id")), issues);

        assertThat(issues).anySatisfy(issue -> issue.message().contains("源字段不存在"));
    }

    @Test
    void emptySourceColumnsMeansAllColumnsMode() {
        TableMetadata target = new TableMetadata(
                "public", "patient",
                List.of(
                        new ColumnMetadata("id", Types.BIGINT, "bigint", 0, false, true),
                        new ColumnMetadata("name", Types.VARCHAR, "varchar", 64, true, false)),
                List.of("id"),
                List.of());

        // Table 模式 selectedColumns 为空 = 全字段，源字段存在性不校验
        List<ValidationReport.Issue> issues = new ArrayList<>();
        validator.validateMappings(List.of(), target,
                List.of(new FieldMapping("id", "id"), new FieldMapping("name", "name")), issues);

        assertThat(issues).isEmpty();
    }

    @Test
    void completeMappingPasses() {
        TableMetadata target = new TableMetadata(
                "public", "patient",
                List.of(
                        new ColumnMetadata("id", Types.BIGINT, "bigint", 0, false, true),
                        new ColumnMetadata("name", Types.VARCHAR, "varchar", 64, true, false)),
                List.of("id"),
                List.of());

        List<ValidationReport.Issue> issues = new ArrayList<>();
        validator.validateMappings(List.of("id", "name"), target,
                List.of(new FieldMapping("id", "id")), issues);

        assertThat(issues).isEmpty();
    }

    @Test
    void updatedTimeFieldWithInsertOnlyIsBlocked() {
        List<Issue> issues = new ArrayList<>();
        validator.validateIncrementalCursorConfiguration(
                new TableReadDefinition("public", "patient", List.of("id", "updated_at"), List.of(),
                        List.of("id"), "updated_at"),
                WriteMode.INSERT_ONLY, List.of(), issues);

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("INCREMENTAL_REQUIRES_UPSERT");
            assertThat(issue.severity()).isEqualTo(Severity.BLOCKING);
            assertThat(issue.field()).isEqualTo("writeMode");
        });
    }

    @Test
    void updatedTimeFieldWithUpsertAndUniqueKeyPasses() {
        List<Issue> issues = new ArrayList<>();
        validator.validateIncrementalCursorConfiguration(
                new TableReadDefinition("public", "patient", List.of("id", "updated_at"), List.of(),
                        List.of("id"), "updated_at"),
                WriteMode.UPSERT, List.of("id"), issues);

        assertThat(issues).isEmpty();
    }

    @Test
    void updatedTimeFieldWithNoOverwriteAndUniqueKeyPasses() {
        List<Issue> issues = new ArrayList<>();
        validator.validateIncrementalCursorConfiguration(
                new TableReadDefinition("public", "patient", List.of("id", "updated_at"), List.of(),
                        List.of("id"), "updated_at"),
                WriteMode.UPSERT_NO_OVERWRITE, List.of("id"), issues);

        assertThat(issues).isEmpty();
    }

    @Test
    void updatedTimeFieldWithNoOverwriteButNoUniqueKeyIsBlocked() {
        List<Issue> issues = new ArrayList<>();
        validator.validateIncrementalCursorConfiguration(
                new TableReadDefinition("public", "patient", List.of("id", "updated_at"), List.of(),
                        List.of("id"), "updated_at"),
                WriteMode.UPSERT_NO_OVERWRITE, List.of(), issues);

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("INCREMENTAL_REQUIRES_UPSERT");
            assertThat(issue.severity()).isEqualTo(Severity.BLOCKING);
            assertThat(issue.field()).isEqualTo("writeMode");
        });
    }

    @Test
    void noUpdatedTimeFieldSkipsIncrementalCheck() {
        List<Issue> issues = new ArrayList<>();
        validator.validateIncrementalCursorConfiguration(
                new TableReadDefinition("public", "patient", List.of("id"), List.of(),
                        List.of("id"), null),
                WriteMode.INSERT_ONLY, List.of(), issues);

        assertThat(issues).isEmpty();
    }

    @Test
    void replaceAllWithUpdatedTimeFieldIsBlocked() {
        List<Issue> issues = new ArrayList<>();
        validator.validateIncrementalCursorConfiguration(
                new TableReadDefinition("public", "user_role", List.of("user_id", "role_id"), List.of(),
                        List.of("user_id", "role_id"), "updated_at"),
                WriteMode.REPLACE_ALL, List.of(), issues);

        assertThat(issues).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("REPLACE_ALL_NO_INCREMENT");
            assertThat(issue.severity()).isEqualTo(Severity.BLOCKING);
            assertThat(issue.field()).isEqualTo("readDefinition.updatedTimeField");
        });
    }

    @Test
    void replaceAllWithoutUpdatedTimeFieldPasses() {
        List<Issue> issues = new ArrayList<>();
        validator.validateIncrementalCursorConfiguration(
                new TableReadDefinition("public", "user_role", List.of("user_id", "role_id"), List.of(),
                        List.of(), null),
                WriteMode.REPLACE_ALL, List.of(), issues);

        assertThat(issues).isEmpty();
    }
}
