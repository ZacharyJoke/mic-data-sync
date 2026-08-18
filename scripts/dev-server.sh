#!/usr/bin/env bash
# 后端开发模式：启动 Spring Boot 应用（默认端口 19090）。
# 应用默认 context-path=/mic-data-sync，与生产保持一致；
# 前端以 Vite 开发模式运行（见 scripts/dev-web.sh），通过 /mic-data-sync/api、/mic-data-sync/actuator 代理访问。
set -euo pipefail

cd "$(dirname "$0")/.."

exec ./mvnw -pl server spring-boot:run
