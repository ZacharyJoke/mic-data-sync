#!/usr/bin/env bash
# E2E 公共函数：环境检查、HTTP 辅助、结果输出
set -euo pipefail

E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS_DIR="$E2E_ROOT/results"
mkdir -p "$RESULTS_DIR"

require_env() {
  local name="$1"
  if [ -z "${!name:-}" ]; then
    echo "错误：缺少环境变量 $name" >&2
    exit 1
  fi
}

require_env MIC_SYNC_ADMIN_PASSWORD

JAR="${MIC_SYNC_JAR:-server/target/mic-data-sync-server-0.1.0-SNAPSHOT.jar}"
[ -f "$JAR" ] || { echo "错误：未找到 JAR：$JAR" >&2; exit 1; }

start_client() {
  local name="$1" data_dir="$2" port="$3"
  MIC_SYNC_DATA_DIR="$data_dir" \
  MIC_SYNC_SERVER_PORT="$port" \
  MIC_SYNC_ADMIN_PASSWORD="$MIC_SYNC_ADMIN_PASSWORD" \
    java -jar "$JAR" > "$data_dir/app.log" 2>&1 &
  echo $!
}

wait_ready() {
  local port="$1"
  for _ in $(seq 1 60); do
    if curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/actuator/health" 2>/dev/null | grep -q 200; then
      return 0
    fi
    sleep 1
  done
  echo "错误：客户端 $port 未就绪" >&2
  return 1
}

api_login() {
  local port="$1" cookie="$2"
  local csrf
  csrf=$(curl -s -c "$cookie" "http://127.0.0.1:$port/api/v1/auth/csrf" | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
  curl -s -b "$cookie" -c "$cookie" -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $csrf" \
    -d "{\"username\":\"admin\",\"password\":\"$MIC_SYNC_ADMIN_PASSWORD\"}" \
    "http://127.0.0.1:$port/api/v1/auth/login" > /dev/null
  echo "$csrf"
}

write_result() {
  local file="$1"
  cat > "$RESULTS_DIR/$file"
  echo "结果已写入：$RESULTS_DIR/$file"
}
