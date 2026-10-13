#!/bin/bash
# 阶段0 基线复跑（审查用，只读业务代码）。原始输出全部落盘到 logs/。
cd /Users/arlan/Downloads/chengyin/chengyinhub-xcx || exit 1
LOGDIR=docs/audit-e2e-202609/logs
mkdir -p "$LOGDIR"
run() {
  name="$1"; shift
  { echo "\$ $*"; echo "---"; "$@"; echo "EXIT=$?"; } > "$LOGDIR/$name.log" 2>&1
  echo "$name EXIT=$(tail -1 "$LOGDIR/$name.log")"
}
run test-unit        npm run test:unit
run lint-ui          npm run lint:ui
run lint-scope       npm run lint:scope
run lint-requestbody npm run lint:requestbody
run audit-actions    npm run audit:actions
run danger-confirm   npm run lint:danger-confirm
run ui-validity      node scripts/ui-validity-gates.js
run dead-ui          npm run test:dead-ui
