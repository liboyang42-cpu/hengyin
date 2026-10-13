#!/bin/bash
# 阶段0/2 的模拟器冒烟四条：CI 一条都不跑（见 06-gate-blindspots.md），这里单独留证。
cd /Users/arlan/Downloads/chengyin/chengyinhub-xcx || exit 1
LOGDIR=docs/audit-e2e-202609/logs
mkdir -p "$LOGDIR"
for t in "test:automator" "test:automator:merchant-ledger" "test:automator:ai-entries" "test:automator:stamp-camera-crop"; do
  name="automator-$(echo "$t" | sed 's/test:automator[: -]*//g')"
  { echo "\$ npm run $t"; echo "---"; npm run "$t"; echo "EXIT=$?"; } > "$LOGDIR/$name.log" 2>&1
  echo "$name $(tail -1 "$LOGDIR/$name.log")"
done
