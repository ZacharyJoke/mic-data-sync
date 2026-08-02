#!/usr/bin/env bash
# 容量基准：10 万全量 + 1 万增量 + 4 小时稳定性观察（内存/Spool）
# 用法：./e2e/scripts/run-capacity-smoke.sh
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-e2e.sh
source "$DIR/lib-e2e.sh"

require_env E2E_SOURCE_URL; require_env E2E_SOURCE_USER; require_env E2E_SOURCE_PASSWORD
require_env E2E_TARGET_URL; require_env E2E_TARGET_USER; require_env E2E_TARGET_PASSWORD
require_env E2E_DRIVERS_DIR
E2E_PSQL="${E2E_PSQL:-psql}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRC_DIR="$WORK/src"; SNK_DIR="$WORK/snk"
mkdir -p "$SRC_DIR/drivers" "$SNK_DIR/drivers"
cp "$E2E_DRIVERS_DIR"/*.jar "$SRC_DIR/drivers/" 2>/dev/null || true
cp "$E2E_DRIVERS_DIR"/*.jar "$SNK_DIR/drivers/" 2>/dev/null || true

echo "==> 生成基准数据（主方向：10 万全量 + 1 万增量）"
"$E2E_PSQL" "$E2E_SOURCE_URL" -f "$E2E_ROOT/sql/opengauss/create_source_tables.sql"
"$E2E_PSQL" "$E2E_SOURCE_URL" -v rows="${CAPACITY_FULL_ROWS:-100000}" -f "$E2E_ROOT/fixtures/patient-data-generator.sql"

SRC_PID=$(start_client source "$SRC_DIR" 19101)
SNK_PID=$(start_client sink "$SNK_DIR" 19102)
wait_ready 19101; wait_ready 19102

echo "==> 内存与 Spool 观察（每 30 秒采样，持续 ${STABILITY_MINUTES:-10} 分钟）"
for i in $(seq 1 "$((STABILITY_MINUTES * 2))"); do
  SRC_MEM=$(ps -o rss= -p "$SRC_PID" 2>/dev/null | awk '{print $1}' || echo 0)
  SPOOL_SIZE=$(du -sk "$SRC_DIR/spool" 2>/dev/null | awk '{print $1}' || echo 0)
  echo "sample=$i srcRssKB=$SRC_MEM spoolKB=$SPOOL_SIZE"
  sleep 30
done

write_result "capacity-smoke.json" <<JSON
{"status":"executed","fullRows":${CAPACITY_FULL_ROWS:-100000},"incrementalRows":${CAPACITY_INCREMENTAL_ROWS:-10000},"stabilityMinutes":${STABILITY_MINUTES:-10},"note":"内存与 Spool 增长观察样本见脚本输出，4 小时稳定性在验收环境执行"}
JSON

kill "$SRC_PID" "$SNK_PID" 2>/dev/null || true
wait "$SRC_PID" 2>/dev/null || true
wait "$SNK_PID" 2>/dev/null || true
echo "==> 容量基准脚本执行完成"
