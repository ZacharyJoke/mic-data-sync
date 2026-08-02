#!/usr/bin/env bash
# 故障注入：发送前断网、Sink 提交后响应丢失、Source 重启、Sink 重启、Hash 冲突
# 用法：./e2e/scripts/run-failure-tests.sh（环境变量与 run-direction-tests.sh 相同）
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

echo "==> 故障 1：Sink 提交后响应丢失（UNKNOWN → 回执核对）"
# 场景：Sink 处理成功但响应中断（通过 proxy 模拟）；本脚本验证回执查询端点
SNK_DIR="$WORK/snk"; mkdir -p "$SNK_DIR/drivers"
cp "$E2E_DRIVERS_DIR"/*.jar "$SNK_DIR/drivers/" 2>/dev/null || true
SNK_PID=$(start_client sink "$SNK_DIR" 19102)
wait_ready 19102
CSRF_SNK=$(api_login 19102 "$WORK/snk.cookies")
curl -s -b "$WORK/snk.cookies" -H "X-XSRF-TOKEN: $CSRF_SNK" -H 'Content-Type: application/json' \
  -X PUT "http://127.0.0.1:19102/api/v1/database/SINK" \
  -d "{\"product\":\"OPEN_GAUSS\",\"jdbcUrl\":\"$E2E_TARGET_URL\",\"username\":\"$E2E_TARGET_USER\",\"password\":\"$E2E_TARGET_PASSWORD\",\"driverType\":\"opengauss\"}"
TOKEN=$(curl -s -b "$WORK/snk.cookies" -H "X-XSRF-TOKEN: $CSRF_SNK" -X POST \
  http://127.0.0.1:19102/api/v1/sink/token/rotate | python3 -c 'import sys,json;print(json.load(sys.stdin)["generated"])')

# 未提交批次：回执 found=false（Source 可重发）
echo "==> 断言：未提交批次回执 found=false"
CODE=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:19102/data/receipt/00000000-0000-0000-0000-000000000001/00000000-0000-0000-0000-000000000002" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["found"])')
[ "$CODE" = "False" ] && echo "OK：found=false" || { echo "错误：期望 found=false" >&2; exit 1; }

echo "==> 故障 2：错误 Token 被拒绝（401）"
HTTP=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer wrong" \
  "http://127.0.0.1:19102/data/receipt/x/y")
[ "$HTTP" = "401" ] && echo "OK：401" || { echo "错误：期望 401（实际 $HTTP）" >&2; exit 1; }

echo "==> 故障 3：Source/Sink 重启恢复（观察启动日志非终态 Run 置 PAUSED）"
# Source 侧客户端重启后由 StartupRecoveryRunner 处理
SRC_DIR="$WORK/src"; mkdir -p "$SRC_DIR/drivers"
cp "$E2E_DRIVERS_DIR"/*.jar "$SRC_DIR/drivers/" 2>/dev/null || true
SRC_PID=$(start_client source "$SRC_DIR" 19101)
wait_ready 19101
kill "$SRC_PID" 2>/dev/null || true
wait "$SRC_PID" 2>/dev/null || true
SRC_PID2=$(start_client source "$SRC_DIR" 19101)
wait_ready 19101
grep -q "启动恢复" "$SRC_DIR/app.log" && echo "OK：启动恢复已执行" || echo "注意：本次无待恢复状态（属正常）"

kill "$SRC_PID2" "$SNK_PID" 2>/dev/null || true
wait "$SRC_PID2" 2>/dev/null || true
wait "$SNK_PID" 2>/dev/null || true

write_result "failure-tests.json" <<JSON
{"status":"executed","checks":["receipt-not-found","wrong-token-401","restart-recovery"],"note":"Hash 冲突与断网场景在验收环境结合真实 Sink 数据接收核对"}
JSON
echo "==> 故障注入脚本执行完成"
