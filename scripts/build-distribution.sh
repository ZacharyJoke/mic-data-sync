#!/usr/bin/env bash
# 集成构建：构建前端并打入后端 JAR，生成统一可执行 JAR。
# 任何一步失败立即退出，不允许忽略前端失败继续打包后端。
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> [1/6] 安装前端依赖（锁定版本）"
npm --prefix web ci

echo "==> [2/6] 构建前端"
npm --prefix web run build

echo "==> [3/6] 拷贝前端产物到后端静态资源目录"
rm -rf server/src/main/resources/static/*
mkdir -p server/src/main/resources/static
cp -R web/dist/* server/src/main/resources/static/

echo "==> [4/6] 打包后端可执行 JAR"
./mvnw -pl server clean package

JAR=$(ls server/target/mic-data-sync-server-*.jar 2>/dev/null | grep -v original | tail -1)
[ -n "$JAR" ] || { echo "错误：未生成可执行 JAR" >&2; exit 1; }

echo "==> [5/6] 组装 Linux 分发包"
DIST_DIR="distribution-build"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"/{bin,config,systemd,nginx,docs,drivers}
cp distribution/bin/start.sh distribution/bin/stop.sh "$DIST_DIR/bin/"
chmod +x "$DIST_DIR"/bin/*.sh
cp distribution/config/application-example.yml "$DIST_DIR/config/"
cp distribution/systemd/mic-data-sync.service "$DIST_DIR/systemd/"
cp distribution/nginx/mic-data-sync.conf "$DIST_DIR/nginx/"
cp "$JAR" "$DIST_DIR/"

# 分发包文档与公开仓库保持一致；docs/internal 为不随版本发布的测试环境记录
cp README.md LICENSE CONTRIBUTING.md SECURITY.md CODE_OF_CONDUCT.md CHANGELOG.md "$DIST_DIR/"
cp -r docs/* "$DIST_DIR/docs/"
rm -rf "$DIST_DIR/docs/internal"

# 数据库 JDBC 驱动：如仓库 distribution/drivers 下已有 JAR 则内置，
# 否则由用户按 drivers/README.txt 说明自行放置（KingbaseES 商业驱动需用户自备）
if ls distribution/drivers/*.jar >/dev/null 2>&1; then
  cp distribution/drivers/*.jar "$DIST_DIR/drivers/"
fi
cat > "$DIST_DIR/drivers/README.txt" <<'DRIVER_EOF'
数据库 JDBC 驱动（默认目录 ${MIC_SYNC_DRIVER_DIR:-<dataDir>/drivers}，可用 MIC_SYNC_DRIVER_DIR 覆盖）：
  KingbaseES：kingbase8-*.jar（驱动类 com.kingbase8.Driver，推荐 8.6.0，支持 scram-sha-256 与 md5）
  openGauss：opengauss-jdbc-*.jar（驱动类 org.opengauss.Driver，推荐 3.0.0）
  openGauss 系兼容库（如 Vastbase）：postgresql-*.jar（驱动类 org.postgresql.Driver，推荐 42.7.13）
如需其他版本：删除同前缀旧 JAR，放入新版（保持 kingbase8- / opengauss-jdbc- / postgresql- 前缀）后重启应用。
DRIVER_EOF

ARCHIVE="mic-data-sync-0.1.0-linux-x86_64.tar.gz"
tar -czf "$ARCHIVE" -C "$DIST_DIR" .
echo "==> [6/6] 构建完成：$JAR"
echo "==> 分发包：$(ls -lh "$ARCHIVE" | awk '{print $NF, $5}')"
