#!/usr/bin/env bash
# 可在开发机或任意 CI runner 执行的发布质量闸门，不读取生产凭据。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "[1/6] 检查提交差异格式"
git diff --check

echo "[2/6] 安装并验证小程序"
cd "$ROOT/chengyinhub-xcx"
npm ci --ignore-scripts
npm run preflight:release
npm run test:unit

echo "[3/6] 验证后端"
cd "$ROOT"
mvn -q -pl chengyinhub-admin -am test

echo "[4/6] 安装并构建管理端"
cd "$ROOT/chengyinhub-ui"
npm ci --ignore-scripts
npm run test:unit
npm run build:prod

echo "[5/6] 审计管理端生产依赖"
npm audit --omit=dev --audit-level=high

echo "[6/6] 质量闸门通过"
