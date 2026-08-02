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
 * <p>驱动目录：{@code ${dataDir}/drivers}，按类型约定 JAR 名前缀：</p>
 * <ul>
 *   <li>KingbaseES：{@code kingbase8-*.jar}，驱动类 {@code com.kingbase8.Driver}；</li>
 *   <li>openGauss：{@code opengauss-jdbc-*.jar}，驱动类 {@code org.opengauss.Driver}。</li>
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

    /** 各类型约定的驱动 JAR 名前缀。 */
    public static final Map<DatabaseType, String> JAR_PREFIX = Map.of(
            DatabaseType.KINGBASE_ES, "kingbase8-",
            DatabaseType.OPEN_GAUSS, "opengauss-jdbc-");

    private DriverLoader() {
    }

    /**
     * 从驱动目录加载指定类型的 JDBC 驱动实例。
     *
     * @throws DriverLoadException 目录缺失、未找到匹配 JAR 或驱动类加载失败时抛出
     */
    public static Driver load(DatabaseType type, Path driverDir) {
        if (!Files.isDirectory(driverDir)) {
            throw new DriverLoadException("驱动目录不存在: " + driverDir
                    + "（请将驱动 JAR 放到该目录后重试）");
        }
        String prefix = JAR_PREFIX.get(type);
        List<Path> jars;
        try (Stream<Path> stream = Files.list(driverDir)) {
            jars = stream
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .filter(p -> p.toString().endsWith(".jar"))
                    .toList();
        } catch (IOException ex) {
            throw new DriverLoadException("扫描驱动目录失败: " + driverDir, ex);
        }
        if (jars.isEmpty()) {
            throw new DriverLoadException("未找到匹配的驱动 JAR（约定前缀: " + prefix + "*.jar）");
        }

        URL[] urls = jars.stream().map(DriverLoader::toUrl).toArray(URL[]::new);
        // 独立 ClassLoader 隔离驱动类，避免与内置类冲突。
        // 注意：不能关闭该 ClassLoader——返回的 Driver 实例后续 connect 时
        // 仍会按需加载内部类，关闭会导致 NoClassDefFoundError（如 Driver$1）。
        URLClassLoader loader = new URLClassLoader(urls, DriverLoader.class.getClassLoader());
        ReflectiveOperationException lastError = null;
        for (String driverClass : DRIVER_CLASSES.getOrDefault(type, List.of())) {
            try {
                Class<?> cls = Class.forName(driverClass, true, loader);
                return (Driver) cls.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException ex) {
                lastError = ex;
            }
        }
        throw new DriverLoadException("驱动类加载失败（候选: " + DRIVER_CLASSES.get(type) + "）", lastError);
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
