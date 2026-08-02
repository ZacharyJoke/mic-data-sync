#!/usr/bin/env bash
# mic-data-sync 停止脚本
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
DATA_DIR="${MIC_SYNC_DATA_DIR:-$APP_HOME/data}"
PID_FILE="$DATA_DIR/app.pid"

if [ ! -f "$PID_FILE" ]; then
  echo "未找到 PID 文件（$PID_FILE），可能未启动"
  exit 0
fi

PID=$(cat "$PID_FILE")
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  for _ in $(seq 1 30); do
    kill -0 "$PID" 2>/dev/null || break
    sleep 1
  done
  kill -9 "$PID" 2>/dev/null || true
  echo "mic-data-sync 已停止（pid=$PID）"
else
  echo "进程 $PID 不存在，清理 PID 文件"
fi
rm -f "$PID_FILE"
