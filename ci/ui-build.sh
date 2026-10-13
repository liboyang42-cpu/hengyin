#!/usr/bin/env bash
# 后台前端(chengyinhub-ui)构建:产出 dist/ 并让产物自报出身(dist/version.json)。
# CI 门禁与部署链路共用这一份 —— 门禁验的就是部署真正跑的路径。
# 用法:bash ci/ui-build.sh <commit-sha>   (从任何目录执行皆可,脚本自己回到仓库根)
set -euo pipefail

cd "$(git rev-parse --show-toplevel)" || exit 1   # 路径均为仓库根相对,见 ci/test_cwd_independence.sh

UI_DIR="chengyinhub-ui"
commit="${1:-}"
[ -n "$commit" ] || { echo "用法: bash ci/ui-build.sh <commit-sha>"; exit 1; }
[ -d "$UI_DIR" ] || { echo "找不到 $UI_DIR"; exit 1; }

cd "$UI_DIR"

echo "== 安装依赖(npm ci)=="
npm ci --no-audit --no-fund

echo "== 单元测试 =="
npm run test:unit

# 路由/权限合约。ci.yml 里有独立的 admin_ui_contract job,但 deploy-ui.yml 的
# workflow_dispatch + force_deploy=true 直接跳到本脚本、绕开整条 CI —— 人工恢复部署
# 恰恰是最需要门禁的时刻。放在这里,正常 CI 与人工恢复共用同一道闸。
echo "== 路由/权限合约(test:e2e:contract)=="
npm run test:e2e:contract

echo "== 构建生产产物(build:prod)=="
npm run build:prod

# version.json 必须在 build 之后写:vue-cli build 会先清空 outputDir。
# 这是 jar 里 git.properties 的前端等价物 —— 产物能自报出身,部署后才有东西可回读。
# 没有这个文件,「UI 上线没上线」就只能靠猜。
[ -d dist ] || { echo "构建没产出 dist/"; exit 1; }
printf '{"commit":"%s","builtAt":"%s"}\n' \
  "$commit" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > dist/version.json

echo "== 产物自报 =="
cat dist/version.json
