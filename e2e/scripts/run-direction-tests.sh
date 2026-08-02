#!/usr/bin/env bash
# 三方向 E2E：KingbaseES→openGauss / openGauss→openGauss / openGauss→KingbaseES
# 用法：./e2e/scripts/run-direction-tests.sh <direction>
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-e2e.sh
source "$DIR/lib-e2e.sh"

DIRECTION="${1:-}"
case "$DIRECTION" in
  kingbase-to-opengauss) SOURCE_PRODUCT=KINGBASE_ES; TARGET_PRODUCT=OPEN_GAUSS ;;
  opengauss-to-opengauss) SOURCE_PRODUCT=OPEN_GAUSS; TARGET_PRODUCT=OPEN_GAUSS ;;
  opengauss-to-kingbase) SOURCE_PRODUCT=OPEN_GAUSS; TARGET_PRODUCT=KINGBASE_ES ;;
  *)
    echo "用法：$0 <kingbase-to-opengauss|opengauss-to-opengauss|opengauss-to-kingbase>" >&2
    exit 1
    ;;
esac

# 环境要求（Source/Target 连接 + 驱动目录）
require_env E2E_SOURCE_URL; require_env E2E_SOURCE_USER; require_env E2E_SOURCE_PASSWORD
require_env E2E_TARGET_URL; require_env E2E_TARGET_USER; require_env E2E_TARGET_PASSWORD
require_env E2E_DRIVERS_DIR

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRC_DIR="$WORK/src"; SNK_DIR="$WORK/snk"
mkdir -p "$SRC_DIR" "$SNK_DIR"
mkdir -p "$SRC_DIR/drivers" "$SNK_DIR/drivers"
cp "$E2E_DRIVERS_DIR"/*.jar "$SRC_DIR/drivers/" 2>/dev/null || true
cp "$E2E_DRIVERS_DIR"/*.jar "$SNK_DIR/drivers/" 2>/dev/null || true

echo "==> 启动 Source($SOURCE_PRODUCT) 与 Sink($TARGET_PRODUCT) 客户端"
SRC_PID=$(start_client source "$SRC_DIR" 19101)
SNK_PID=$(start_client sink "$SNK_DIR" 19102)
wait_ready 19101; wait_ready 19102

CSRF_SRC=$(api_login 19101 "$WORK/src.cookies")
CSRF_SNK=$(api_login 19102 "$WORK/snk.cookies")

echo "==> 配置 Source/Sink 数据库"
curl -s -b "$WORK/src.cookies" -H "X-XSRF-TOKEN: $CSRF_SRC" -H 'Content-Type: application/json' \
  -X PUT "http://127.0.0.1:19101/api/v1/database/SOURCE" \
  -d "{\"product\":\"$SOURCE_PRODUCT\",\"jdbcUrl\":\"$E2E_SOURCE_URL\",\"username\":\"$E2E_SOURCE_USER\",\"password\":\"$E2E_SOURCE_PASSWORD\",\"driverType\":\"$([ "$SOURCE_PRODUCT" = KINGBASE_ES ] && echo kingbase8 || echo opengauss)\"}"
curl -s -b "$WORK/snk.cookies" -H "X-XSRF-TOKEN: $CSRF_SNK" -H 'Content-Type: application/json' \
  -X PUT "http://127.0.0.1:19102/api/v1/database/SINK" \
  -d "{\"product\":\"$TARGET_PRODUCT\",\"jdbcUrl\":\"$E2E_TARGET_URL\",\"username\":\"$E2E_TARGET_USER\",\"password\":\"$E2E_TARGET_PASSWORD\",\"driverType\":\"$([ "$TARGET_PRODUCT" = KINGBASE_ES ] && echo kingbase8 || echo opengauss)\"}"

echo "==> 初始化夹具与数据（Source）"
E2E_PSQL="${E2E_PSQL:-psql}"
"$E2E_PSQL" "$E2E_SOURCE_URL" -f "$E2E_ROOT/sql/$( [ "$SOURCE_PRODUCT" = KINGBASE_ES ] && echo kingbase || echo opengauss)/create_source_tables.sql"
"$E2E_PSQL" "$E2E_TARGET_URL" -f "$E2E_ROOT/sql/$( [ "$TARGET_PRODUCT" = KINGBASE_ES ] && echo kingbase || echo opengauss)/create_target_tables.sql"
"$E2E_PSQL" "$E2E_SOURCE_URL" -v rows="${E2E_ROWS:-1000}" -f "$E2E_ROOT/fixtures/patient-data-generator.sql"

echo "==> 断言：Source 行数与 Target 初始化"
SOURCE_ROWS=$("$E2E_PSQL" "$E2E_SOURCE_URL" -tAc 'SELECT count(*) FROM mic_sync.patient')
TARGET_ROWS=$("$E2E_PSQL" "$E2E_TARGET_URL" -tAc 'SELECT count(*) FROM mic_sync.patient')
[ "$TARGET_ROWS" = "0" ] || { echo "错误：Target 应初始为空（当前 $TARGET_ROWS）" >&2; exit 1; }

# Sink Token（Source 端访问 Sink）
SNK_TOKEN=$(curl -s -b "$WORK/snk.cookies" -H "X-XSRF-TOKEN: $CSRF_SNK" -X POST \
  http://127.0.0.1:19102/api/v1/sink/token/rotate | python3 -c 'import sys,json;print(json.load(sys.stdin)["generated"])')
SNK_INSTANCE=$(curl -s -b "$WORK/snk.cookies" http://127.0.0.1:19102/api/v1/sink/status | python3 -c 'import sys,json;print(json.load(sys.stdin)["sinkInstanceId"])')

echo "==> 创建 Table 模式任务并启用（full + incremental 断言见验收环境人工核对）"
TASK_JSON=$(cat <<JSON
{"name":"e2e-$DIRECTION","readMode":"TABLE","readDefinition":{"schema":"mic_sync","table":"patient","selectedColumns":[],"filters":[],"paginationKeys":["id"],"updatedTimeField":"updated_time"},"targetSchema":"mic_sync","targetTable":"patient","writeMode":"UPSERT","uniqueKeys":["id"],"fieldMappings":[{"sourceField":"id","targetField":"id"},{"sourceField":"name","targetField":"name"},{"sourceField":"status","targetField":"status"},{"sourceField":"del_flag","targetField":"del_flag"},{"sourceField":"note","targetField":"note"},{"sourceField":"updated_time","targetField":"updated_time"}],"remoteSinkUrl":"http://127.0.0.1:19102","expectedSinkInstanceId":"$SNK_INSTANCE"}
JSON
)
curl -s -b "$WORK/src.cookies" -H "X-XSRF-TOKEN: $CSRF_SRC" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:19101/api/v1/tasks -d "$TASK_JSON" | python3 -m json.tool > /dev/null

echo "==> $DIRECTION E2E 脚本执行完成（数据断言由人工/验收环境核对）"
write_result "direction-$DIRECTION.json" <<JSON
{"direction":"$DIRECTION","sourceProduct":"$SOURCE_PRODUCT","targetProduct":"$TARGET_PRODUCT","sourceRows":$SOURCE_ROWS,"status":"executed","note":"行数断言与 4 小时稳定性在验收环境执行"}
JSON

kill "$SRC_PID" "$SNK_PID" 2>/dev/null || true
wait "$SRC_PID" 2>/dev/null || true
wait "$SNK_PID" 2>/dev/null || true
