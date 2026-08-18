package com.mic.datasync.database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 从约定的本地驱动目录加载真实 JDBC 驱动（不实现 Web 上传）。
 *
 * <p>驱动目录：默认 {@code ${dataDir}/drivers}，可由 {@code mic.sync.driver-dir}
 * 覆盖，按类型约定 JAR 名前缀：</p>
 * <ul>
 *   <li>KingbaseES：{@code kingbase8-*.jar}，驱动类 {@code com.kingbase8.Driver}；</li>
 *   <li>openGauss：{@code opengauss-jdbc-*.jar} 或 {@code postgresql-*.jar}，
 *       驱动类 {@code org.opengauss.Driver} / {@code org.postgresql.Driver}；
 *       基于 openGauss 内核且兼容 PostgreSQL 协议的数据库（如 Vastbase）可使用
 *       PostgreSQL 驱动（{@code jdbc:postgresql://} URL）。</li>
 * </ul>
 */
public final class DriverLoader {

    /**
     * 各类型候选驱动类名（按顺序尝试）：
     * openGauss 5.x 驱动基于 PostgreSQL 驱动，主类为 org.postgresql.Driver；
     * 旧版本（3.x）使用 org.opengauss.Driver，两种均兼容。
     */
    public static final Map<DatabaseType, List<String>> DRIVER_CLASSES = Map.of(
            DatabaseType.KINGBASE_ES, List.of("com.kingbase8.Driver"),
            DatabaseType.OPEN_GAUSS, List.of("org.opengauss.Driver", "org.postgresql.Driver"));

    /** 各类型约定的驱动 JAR 名前缀（任一匹配即可）。 */
    public static final Map<DatabaseType, List<String>> JAR_PREFIX = Map.of(
            DatabaseType.KINGBASE_ES, List.of("kingbase8-"),
            DatabaseType.OPEN_GAUSS, List.of("opengauss-jdbc-", "postgresql-"));

    private DriverLoader() {
    }

    /**
     * 从驱动目录加载指定类型的 JDBC 驱动实例。
     *
     * @throws DriverLoadException 目录缺失、未找到匹配 JAR 或驱动类加载失败时抛出
     */
    public static Driver load(DatabaseType type, Path driverDir, String jdbcUrl) {
        if (!Files.isDirectory(driverDir)) {
            throw new DriverLoadException("驱动目录不存在: " + driverDir
                    + "（请将驱动 JAR 放到该目录后重试）");
        }
        List<String> prefixes = JAR_PREFIX.get(type);
        List<Path> jars;
        try (Stream<Path> stream = Files.list(driverDir)) {
            jars = stream
                    .filter(p -> prefixes.stream()
                            .anyMatch(prefix -> p.getFileName().toString().startsWith(prefix)))
                    .filter(p -> p.toString().endsWith(".jar"))
                    .toList();
        } catch (IOException ex) {
            throw new DriverLoadException("扫描驱动目录失败: " + driverDir, ex);
        }
        if (jars.isEmpty()) {
            String prefixText = prefixes.stream()
                    .map(prefix -> prefix + "*.jar")
                    .collect(java.util.stream.Collectors.joining(" / "));
            throw new DriverLoadException("未找到匹配的驱动 JAR（约定前缀: " + prefixText + "）");
        }

        URL[] urls = jars.stream().map(DriverLoader::toUrl).toArray(URL[]::new);
        // 独立 ClassLoader 隔离驱动类，避免与内置类冲突。
        // 注意：不能关闭该 ClassLoader——返回的 Driver 实例后续 connect 时
        // 仍会按需加载内部类，关闭会导致 NoClassDefFoundError（如 Driver$1）。
        URLClassLoader loader = new URLClassLoader(urls, DriverLoader.class.getClassLoader());
        ReflectiveOperationException lastError = null;
        List<String> candidates = candidateDriverClasses(type, jdbcUrl);
        for (String driverClass : candidates) {
            try {
                Class<?> cls = Class.forName(driverClass, true, loader);
                return (Driver) cls.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException ex) {
                lastError = ex;
            }
        }
        throw new DriverLoadException("驱动类加载失败（候选: " + candidates + "）", lastError);
    }

    /**
     * 按 JDBC URL 协议返回驱动类候选顺序：URL 使用的协议优先匹配对应的驱动类，
     * 使 openGauss 驱动与 PostgreSQL 驱动可以共存于同一驱动目录。
     */
    static List<String> candidateDriverClasses(DatabaseType type, String jdbcUrl) {
        List<String> defaults = DRIVER_CLASSES.getOrDefault(type, List.of());
        if (type != DatabaseType.OPEN_GAUSS || jdbcUrl == null) {
            return defaults;
        }
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            return List.of("org.postgresql.Driver", "org.opengauss.Driver");
        }
        if (jdbcUrl.startsWith("jdbc:opengauss:")) {
            return List.of("org.opengauss.Driver", "org.postgresql.Driver");
        }
        return defaults;
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** 驱动加载失败。 */
    public static class DriverLoadException extends RuntimeException {
        public DriverLoadException(String message) {
            super(message);
        }

        public DriverLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
