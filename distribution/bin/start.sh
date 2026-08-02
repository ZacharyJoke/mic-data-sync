#!/usr/bin/env bash
# mic-data-sync 启动脚本
# 角色：source / sink / source,sink（默认 source,sink）
# 运行环境要求：操作系统提供 Java 21（不在应用包内捆绑 JDK）
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$APP_HOME/mic-data-sync-server-0.1.0-SNAPSHOT.jar"

ROLES="${MIC_SYNC_ROLES:-source,sink}"
PORT="${MIC_SYNC_SERVER_PORT:-19090}"
DATA_DIR="${MIC_SYNC_DATA_DIR:-$APP_HOME/data}"

[ -f "$JAR" ] || { echo "错误：未找到 $JAR" >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "错误：需要 Java 21，请先安装 JDK 并配置 PATH" >&2; exit 1; }

mkdir -p "$DATA_DIR" "$DATA_DIR/drivers"
# 驱动目录说明
if [ ! -f "$DATA_DIR/drivers/README.txt" ]; then
  cat > "$DATA_DIR/drivers/README.txt" <<'TXT'
将数据库 JDBC 驱动 JAR 放到本目录：
  KingbaseES：kingbase8-*.jar（驱动类 com.kingbase8.Driver）
  openGauss：opengauss-jdbc-*.jar（驱动类 org.opengauss.Driver）
TXT
fi

ARGS=(-jar "$JAR" --mic.sync.roles="$ROLES" --server.port="$PORT" --mic.sync.data-dir="$DATA_DIR")
if [ -n "${MIC_SYNC_ADMIN_PASSWORD:-}" ]; then ARGS+=(--mic.sync.admin.password="$MIC_SYNC_ADMIN_PASSWORD"); fi
if [ -n "${MIC_SYNC_MASTER_KEY:-}" ]; then ARGS+=(--mic.sync.master-key="$MIC_SYNC_MASTER_KEY"); fi
if [ -n "${MIC_SYNC_SINK_TOKEN:-}" ]; then ARGS+=(--mic.sync.sink-token="$MIC_SYNC_SINK_TOKEN"); fi

nohup java "${ARGS[@]}" >> "$DATA_DIR/app.log" 2>&1 &
PID=$!
echo "$PID" > "$DATA_DIR/app.pid"
echo "mic-data-sync 已启动：roles=$ROLES port=$PORT pid=$PID"
echo "日志：$DATA_DIR/app.log"
echo "提示：建议使用独立系统账号运行（root 可运行但不推荐）"
