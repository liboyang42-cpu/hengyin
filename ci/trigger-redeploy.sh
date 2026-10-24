#!/usr/bin/env bash
# Automatic redeployment is disabled; keep callers fail-closed and give a manual recovery path.
set -euo pipefail
WORKFLOW="${1:?缺少 workflow 名}"
printf '::error::自动补部署已关闭；请人工选择通过完整 CI 的精确 SHA 后在 %s Run workflow\n' "$WORKFLOW"
exit 1
