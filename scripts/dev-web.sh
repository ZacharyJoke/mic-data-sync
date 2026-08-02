#!/usr/bin/env bash
# 前端开发模式：启动 Vite 开发服务器，代理 /api 与 /actuator 到本地后端。
set -euo pipefail

cd "$(dirname "$0")/.."

exec npm --prefix web run dev
