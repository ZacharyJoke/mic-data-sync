#!/usr/bin/env bash
# Smoke Test：启动打包后的 JAR 到临时数据目录，
# 验证 readiness、系统 ping 与 SPA 首页，结束后停止进程并清理临时目录。
set -euo pipefail

cd "$(dirname "$0")/.."

JAR="${1:-server/target/mic-data-sync-server-0.1.0-SNAPSHOT.jar}"
PORT="${SMOKE_PORT:-19090}"
DATA_DIR="$(mktemp -d)"
APP_PID=""

if [ ! -f "$JAR" ]; then
  echo "错误：未找到 JAR：${JAR}，请先运行 scripts/build-distribution.sh" >&2
  exit 1
fi

cleanup() {
  if [ -n "$APP_PID" ]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  rm -rf "$DATA_DIR"
}
trap cleanup EXIT

echo "==> 启动应用（临时数据目录：${DATA_DIR}）"
MIC_SYNC_DATA_DIR="$DATA_DIR" \
MIC_SYNC_SERVER_PORT="$PORT" \
  java -jar "$JAR" > "$DATA_DIR/app.log" 2>&1 &
APP_PID=$!

http_code() {
  curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}${1}"
}

echo "==> 等待应用就绪（轮询 readiness）"
ready=0
for _ in $(seq 1 60); do
  if [ "$(http_code /actuator/health/readiness 2>/dev/null)" = "200" ]; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "错误：应用未在 60 秒内就绪，最近日志：" >&2
  tail -20 "$DATA_DIR/app.log" >&2
  exit 1
fi

check_ok() {
  local label="$1" path="$2"
  local code
  code=$(http_code "$path")
  if [ "$code" != "200" ]; then
    echo "失败：${label}（HTTP ${code}）" >&2
    exit 1
  fi
  echo "通过：${label}（HTTP 200）"
}

echo "==> [1/3] 就绪探针"
check_ok "readiness" /actuator/health/readiness

echo "==> [2/3] 系统接口"
check_ok "system ping" /api/v1/system/ping

echo "==> [3/3] SPA 首页"
body=$(curl -s "http://127.0.0.1:$PORT/")
if printf '%s' "$body" | grep -q 'id="app"'; then
  echo "通过：首页包含前端根节点 id=\"app\""
else
  echo "失败：首页未包含前端根节点" >&2
  printf '%s\n' "$body" | head -5 >&2
  exit 1
fi

echo "==> smoke-test 全部通过"
