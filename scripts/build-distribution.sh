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
mkdir -p "$DIST_DIR"/{bin,config,systemd,docs,drivers}
cp distribution/bin/start.sh distribution/bin/stop.sh "$DIST_DIR/bin/"
chmod +x "$DIST_DIR"/bin/*.sh
cp distribution/config/application-example.yml "$DIST_DIR/config/"
cp distribution/systemd/mic-data-sync.service "$DIST_DIR/systemd/"
cp "$JAR" "$DIST_DIR/"

# 分发包文档与公开仓库保持一致；docs/internal 为不随版本发布的测试环境记录
cp README.md LICENSE CONTRIBUTING.md SECURITY.md CODE_OF_CONDUCT.md CHANGELOG.md "$DIST_DIR/"
cp -r docs/* "$DIST_DIR/docs/"
rm -rf "$DIST_DIR/docs/internal"
cat > "$DIST_DIR/drivers/README.txt" <<'DRIVER_EOF'
将数据库 JDBC 驱动 JAR 放到运行目录 data/drivers 下：
  KingbaseES：kingbase8-*.jar（驱动类 com.kingbase8.Driver）
  openGauss：opengauss-jdbc-*.jar（驱动类 org.opengauss.Driver）
DRIVER_EOF

ARCHIVE="mic-data-sync-0.1.0-linux.tar.gz"
tar -czf "$ARCHIVE" -C "$DIST_DIR" .
echo "==> [6/6] 构建完成：$JAR"
echo "==> 分发包：$(ls -lh "$ARCHIVE" | awk '{print $NF, $5}')"
