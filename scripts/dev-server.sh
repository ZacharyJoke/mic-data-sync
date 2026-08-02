#!/usr/bin/env bash
# 后端开发模式：启动 Spring Boot 应用（默认端口 19090）。
# 前端以 Vite 开发模式运行（见 scripts/dev-web.sh），通过 /api、/actuator 代理访问。
set -euo pipefail

cd "$(dirname "$0")/.."

exec ./mvnw -pl server spring-boot:run
