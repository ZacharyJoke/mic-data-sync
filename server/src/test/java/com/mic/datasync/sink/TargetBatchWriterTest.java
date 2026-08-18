package com.mic.datasync.sink;

import com.mic.datasync.database.DatabaseAdapterFactory;
import com.mic.datasync.database.DatabaseType;
import com.mic.datasync.database.TargetDatabaseAdapter;
import com.mic.datasync.database.metadata.ColumnMetadata;
import com.mic.datasync.database.metadata.TableMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 目标写入类型兼容测试：源 smallint 0/1 写入目标 boolean 列时自动转换。
 */
@ExtendWith(MockitoExtension.class)
class TargetBatchWriterTest {

    @Mock
    private Connection connection;
    @Mock
    private PreparedStatement statement;
    @Mock
    private DatabaseAdapterFactory adapterFactory;
    @Mock
    private TargetDatabaseAdapter adapter;

    private TargetBatchWriter writer;

    @BeforeEach
    void setUp() throws Exception {
        writer = new TargetBatchWriter(adapterFactory);
        when(adapterFactory.targetAdapter(any())).thenReturn(adapter);
        when(adapter.buildUpsertSql(anyString(), anyString(), any(), any(), any(),
                anyBoolean(), any(), anyBoolean()))
                .thenReturn("INSERT ... ON DUPLICATE KEY UPDATE ...");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
    }

    private TableMetadata metadata() {
        return new TableMetadata(
                "guangdong", "hospital",
                List.of(
                        new ColumnMetadata("id", Types.BIGINT, "bigint", 0, false, true),
                        new ColumnMetadata("is_active", Types.BOOLEAN, "boolean", 0, false, false)),
                List.of("id"),
                List.of());
    }

    @Test
    void convertsSmallintNumberToBooleanForBooleanTargetColumn() throws Exception {
        writer.upsert(connection, DatabaseType.OPEN_GAUSS, "guangdong", "hospital",
                List.of("id", "is_active"), List.of("id"),
                List.of(List.of(1L, 1L)), metadata(), false, false);

        verify(statement).setObject(eq(1), eq(1L));
        verify(statement).setObject(eq(2), eq(Boolean.TRUE));
    }

    @Test
    void convertsZeroToFalseForBooleanTargetColumn() throws Exception {
        writer.upsert(connection, DatabaseType.OPEN_GAUSS, "guangdong", "hospital",
                List.of("id", "is_active"), List.of("id"),
                List.of(List.of(2L, 0L)), metadata(), false, false);

        verify(statement).setObject(eq(2), eq(Boolean.FALSE));
    }

    @Test
    void convertsStringTrueFalseForBooleanTargetColumn() throws Exception {
        writer.upsert(connection, DatabaseType.OPEN_GAUSS, "guangdong", "hospital",
                List.of("id", "is_active"), List.of("id"),
                List.of(List.of(3L, "true"), List.of(4L, "0")), metadata(), false, false);

        verify(statement).setObject(2, Boolean.TRUE);
        verify(statement).setObject(2, Boolean.FALSE);
    }

    @Test
    void passesThroughNullAndNonBooleanColumns() throws Exception {
        writer.upsert(connection, DatabaseType.OPEN_GAUSS, "guangdong", "hospital",
                List.of("id", "is_active"), List.of("id"),
                List.of(Arrays.asList(5L, null)), metadata(), false, false);

        verify(statement).setObject(eq(1), eq(5L));
        verify(statement).setObject(eq(2), isNull());
    }

    @Test
    void skipConflictSelectsNonKeyColumnAsNoOpColumnForOpenGaussSyntax() throws Exception {
        writer.upsert(connection, DatabaseType.OPEN_GAUSS, "guangdong", "hospital",
                List.of("id", "is_active"), List.of("id"),
                List.of(List.of(1L, Boolean.TRUE)), metadata(), true, false);

        verify(adapter).buildUpsertSql(eq("guangdong"), eq("hospital"),
                eq(List.of("id", "is_active")), eq(List.of("id")), any(),
                eq(true), eq("is_active"), eq(false));
    }

    @Test
    void postgresProtocolSkipConflictDoesNotRequireNoOpColumn() throws Exception {
        writer.upsert(connection, DatabaseType.OPEN_GAUSS, "guangdong", "hospital",
                List.of("id", "is_active"), List.of("id"),
                List.of(List.of(1L, Boolean.TRUE)), metadata(), true, true);

        verify(adapter).buildUpsertSql(eq("guangdong"), eq("hospital"),
                eq(List.of("id", "is_active")), eq(List.of("id")), any(),
                eq(true), isNull(), eq(true));
    }

}
