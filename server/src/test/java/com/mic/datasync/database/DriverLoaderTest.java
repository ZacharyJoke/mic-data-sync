package com.mic.datasync.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverLoaderTest {

    @Test
    void postgresqlUrlPrefersPostgresqlDriverForOpenGaussType() {
        assertEquals(
                List.of("org.postgresql.Driver", "org.opengauss.Driver"),
                DriverLoader.candidateDriverClasses(
                        DatabaseType.OPEN_GAUSS,
                        "jdbc:postgresql://100.100.38.33:5432/guangdong"));
    }

    @Test
    void opengaussUrlPrefersOpengaussDriver() {
        assertEquals(
                List.of("org.opengauss.Driver", "org.postgresql.Driver"),
                DriverLoader.candidateDriverClasses(
                        DatabaseType.OPEN_GAUSS,
                        "jdbc:opengauss://100.100.38.33:5432/guangdong"));
    }

    @Test
    void kingbaseCandidatesAreIndependentOfUrl() {
        assertEquals(
                List.of("com.kingbase8.Driver"),
                DriverLoader.candidateDriverClasses(
                        DatabaseType.KINGBASE_ES,
                        "jdbc:kingbase8://localhost:54321/test"));
    }

    @Test
    void unknownUrlFallsBackToDefaultCandidates() {
        assertEquals(
                List.of("org.opengauss.Driver", "org.postgresql.Driver"),
                DriverLoader.candidateDriverClasses(
                        DatabaseType.OPEN_GAUSS,
                        "jdbc:other://host:5432/db"));
    }

    @Test
    void postgresqlJarPrefixIsAcceptedForOpenGaussType(@TempDir Path driverDir) throws Exception {
        Files.writeString(driverDir.resolve("postgresql-42.7.4.jar"), "fake");
        Files.writeString(driverDir.resolve("unrelated.jar"), "fake");

        DriverLoader.DriverLoadException ex = assertThrows(
                DriverLoader.DriverLoadException.class,
                () -> DriverLoader.load(
                        DatabaseType.OPEN_GAUSS, driverDir,
                        "jdbc:postgresql://host:5432/db"));

        assertTrue(ex.getMessage().contains("驱动类加载失败"),
                "匹配到 postgresql-*.jar 后应进入驱动类加载阶段，而不是未找到 JAR: " + ex.getMessage());
    }

    @Test
    void unmatchedJarPrefixIsRejected(@TempDir Path driverDir) throws Exception {
        Files.writeString(driverDir.resolve("some-driver-1.0.jar"), "fake");

        DriverLoader.DriverLoadException ex = assertThrows(
                DriverLoader.DriverLoadException.class,
                () -> DriverLoader.load(
                        DatabaseType.OPEN_GAUSS, driverDir,
                        "jdbc:postgresql://host:5432/db"));

        assertTrue(ex.getMessage().contains("未找到匹配的驱动 JAR"));
    }

    @Test
    void opengaussAndPostgresqlJarsCanCoexist(@TempDir Path driverDir) throws Exception {
        Files.writeString(driverDir.resolve("opengauss-jdbc-3.0.0.jar"), "fake");
        Files.writeString(driverDir.resolve("postgresql-42.7.4.jar"), "fake");

        DriverLoader.DriverLoadException ex = assertThrows(
                DriverLoader.DriverLoadException.class,
                () -> DriverLoader.load(
                        DatabaseType.OPEN_GAUSS, driverDir,
                        "jdbc:postgresql://host:5432/db"));

        // 两个 JAR 都被识别；postgresql:// 下候选先试 org.postgresql.Driver（fake JAR 中不存在）
        assertTrue(ex.getMessage().contains("驱动类加载失败"));
        assertTrue(ex.getMessage().contains("org.postgresql.Driver"));
    }
}
