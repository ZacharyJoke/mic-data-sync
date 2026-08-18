#!/usr/bin/env bash
# 前端开发模式：启动 Vite 开发服务器，代理 /mic-data-sync/api 与 /mic-data-sync/actuator 到本地后端。
set -euo pipefail

cd "$(dirname "$0")/.."

exec npm --prefix web run dev
