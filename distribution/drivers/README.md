# JDBC 驱动放置说明

应用启动时从 `${MIC_SYNC_DRIVER_DIR:-<dataDir>/drivers}` 目录加载数据库 JDBC 驱动。
分发包构建时，如果本目录下存在 JAR 会自动打入分发包；默认仓库不携带二进制驱动，
请按数据库类型放置对应驱动：

| 数据库 | JAR 前缀 | 驱动类 | 推荐版本 |
|---|---|---|---|
| openGauss | `opengauss-jdbc-*.jar` | `org.opengauss.Driver` | 3.0.0 |
| KingbaseES | `kingbase8-*.jar` | `com.kingbase8.Driver` | 8.6.0（支持 scram-sha-256 与 md5；旧版 8.2.0 不支持 SCRAM） |
| openGauss 系兼容库（如 Vastbase） | `postgresql-*.jar` | `org.postgresql.Driver` | 42.7.13 |

openGauss 兼容库（`jdbc:postgresql://` URL）额外支持 PostgreSQL 驱动。驱动类按
URL 协议自动匹配。如需其他版本，删除同前缀旧 JAR 后放入新版，请勿同时保留同前缀
多个版本；KingbaseES 驱动为商业授权组件，请从人大金仓官方渠道获取并遵守其授权条款。
