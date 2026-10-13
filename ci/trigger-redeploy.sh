#!/usr/bin/env bash
# 漂移对账的自愈触发器：gh 失败必须向调用者传播非零。
set -euo pipefail

WORKFLOW="${1:?缺少 workflow 名}"
if ! gh workflow run "$WORKFLOW" -f ref=master -f force_deploy=false; then
  echo "::error::触发 $WORKFLOW 失败 —— 自愈没发生,请手动去 Actions 里 Run workflow"
  exit 1
fi

echo "已接受 $WORKFLOW 补部署请求(回执不是部署观测)"
