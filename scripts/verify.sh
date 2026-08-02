#!/usr/bin/env bash
# 统一验证入口：后端测试/打包、前端 lint/type-check/test/build。
# 由 M1 脚手架门禁调用，任何一步失败即整体失败。
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> [1/6] 后端 verify (server)"
./mvnw -pl server verify

echo "==> [2/6] 前端 lint"
npm --prefix web run lint

echo "==> [3/6] 前端 type-check"
npm --prefix web run type-check

echo "==> [4/6] 前端 test"
npm --prefix web run test -- --run

echo "==> [5/6] 前端 build"
npm --prefix web run build

echo "==> [6/6] 前端 e2e"
npm --prefix web run test:e2e

echo "==> verify.sh 全部通过"
