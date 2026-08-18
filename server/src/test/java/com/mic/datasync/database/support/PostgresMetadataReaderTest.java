package com.mic.datasync.database.support;

import com.mic.datasync.database.metadata.TableMetadata;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresMetadataReaderTest {

    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData meta = mock(DatabaseMetaData.class);

    @Test
    void listTablesPassesSchemaAsSchemaPattern() throws Exception {
        when(connection.getMetaData()).thenReturn(meta);
        ResultSet rs = mock(ResultSet.class);
        when(meta.getTables(
                isNull(),
                eq("mic_sync"),
                eq("%"),
                argThat(types -> Arrays.equals(types, new String[]{"TABLE"})))).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("TABLE_NAME")).thenReturn("patient", "sales_order");

        assertThat(PostgresMetadataReader.listTables(connection, "mic_sync"))
                .containsExactly("patient", "sales_order");

        verify(meta).getTables(
                isNull(),
                eq("mic_sync"),
                eq("%"),
                argThat(types -> Arrays.equals(types, new String[]{"TABLE"})));
    }

    @Test
    void readTableMetadataPassesSchemaAsSchemaPattern() throws Exception {
        when(connection.getMetaData()).thenReturn(meta);

        ResultSet columns = mock(ResultSet.class);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("COLUMN_NAME")).thenReturn("id");
        when(columns.getInt("DATA_TYPE")).thenReturn(Types.BIGINT);
        when(columns.getString("TYPE_NAME")).thenReturn("int8");
        when(columns.getInt("COLUMN_SIZE")).thenReturn(19);
        when(columns.getInt("NULLABLE")).thenReturn(DatabaseMetaData.columnNoNulls);
        when(meta.getColumns(isNull(), eq("mic_sync"), eq("patient"), eq("%"))).thenReturn(columns);

        ResultSet primaryKeys = mock(ResultSet.class);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("id");
        when(primaryKeys.getInt("KEY_SEQ")).thenReturn(1);
        when(meta.getPrimaryKeys(isNull(), eq("mic_sync"), eq("patient"))).thenReturn(primaryKeys);

        ResultSet indexes = mock(ResultSet.class);
        when(indexes.next()).thenReturn(false);
        when(meta.getIndexInfo(isNull(), eq("mic_sync"), eq("patient"), eq(true), eq(false))).thenReturn(indexes);

        TableMetadata metadata = PostgresMetadataReader.readTableMetadata(connection, "mic_sync", "patient");

        assertThat(metadata.schema()).isEqualTo("mic_sync");
        assertThat(metadata.table()).isEqualTo("patient");
        assertThat(metadata.columns()).hasSize(1);
        assertThat(metadata.columns().getFirst().name()).isEqualTo("id");
        assertThat(metadata.columns().getFirst().primaryKey()).isTrue();
        assertThat(metadata.primaryKeyColumns()).containsExactly("id");

        verify(meta).getColumns(isNull(), eq("mic_sync"), eq("patient"), eq("%"));
        verify(meta).getPrimaryKeys(isNull(), eq("mic_sync"), eq("patient"));
        verify(meta).getIndexInfo(isNull(), eq("mic_sync"), eq("patient"), eq(true), eq(false));
    }

    @Test
    void uniqueIndexColumnsFollowOrdinalPosition() throws Exception {
        when(connection.getMetaData()).thenReturn(meta);

        ResultSet columns = mock(ResultSet.class);
        when(columns.next()).thenReturn(false);
        when(meta.getColumns(isNull(), eq("mic_sync"), eq("sales_order"), eq("%"))).thenReturn(columns);

        ResultSet primaryKeys = mock(ResultSet.class);
        when(primaryKeys.next()).thenReturn(false);
        when(meta.getPrimaryKeys(isNull(), eq("mic_sync"), eq("sales_order"))).thenReturn(primaryKeys);

        ResultSet indexes = mock(ResultSet.class);
        when(indexes.next()).thenReturn(true, true, false);
        when(indexes.getString("INDEX_NAME")).thenReturn("ux_order", "ux_order");
        when(indexes.getString("COLUMN_NAME")).thenReturn("order_no", "id");
        when(indexes.getInt("ORDINAL_POSITION")).thenReturn(2, 1);
        when(meta.getIndexInfo(isNull(), eq("mic_sync"), eq("sales_order"), eq(true), eq(false))).thenReturn(indexes);

        TableMetadata metadata = PostgresMetadataReader.readTableMetadata(connection, "mic_sync", "sales_order");

        assertThat(metadata.uniqueIndexes()).containsExactly(List.of("id", "order_no"));
    }

    @Test
    void listSchemasExcludesPostgresAndOpenGaussSystemSchemas() throws Exception {
        when(connection.getMetaData()).thenReturn(meta);
        ResultSet rs = mock(ResultSet.class);
        when(meta.getSchemas()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, true, true, true, true, true, false);
        when(rs.getString("TABLE_SCHEM")).thenReturn(
                "mic_sync", "public", "dbe_perf", "snapshot", "pkg_service", "pg_catalog", "information_schema");

        assertThat(PostgresMetadataReader.listSchemas(connection)).containsExactly("mic_sync", "public");
    }
}
