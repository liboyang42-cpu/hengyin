#!/usr/bin/env bash
# [f-money2 rig] 一次完整资金验收：新库 → 链路夹具把主题带到 SELLING → 编入通道模拟器 → 跑四类资金链。
# 用法：RIG_SWITCHES=off|on bash run-money.sh   （需要 $CAND 是候选工作树、$HOME/rig 是 rig 分支工作树）
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export RIG_HOME="${RIG_HOME:-$HOME/walk-rig}" CAND="${CAND:-$HOME/cand}" RIG_SKIP_BUILD="${RIG_SKIP_BUILD:-1}"
export RIG="${RIG:-$HOME/rig/docs/delivery/cloud-walk-20261101/rig}" SHA="$(git -C "$CAND" rev-parse HEAD)"
MODE="${RIG_SWITCHES:-off}"
bash "$RIG/up.sh" > "$RIG_HOME/up-$MODE.log" 2>&1 || { tail -20 "$RIG_HOME/up-$MODE.log"; exit 1; }
# [HARNESS] sql/base_schema_20260818.sql 声称已含 migration_20260815_tandianri_h8_publisher_points_funds.sql，
# 实际缺 cms_points_setting.rule_version，活动结算读积分规则时 1054。按原迁移文件原样补一次（迁移自带幂等守卫）。
M="$(docker ps --format '{{.Names}}' | grep -i mysql | head -1)"
docker exec -i "$M" mysql -uroot cy_e2e_51 < "$CAND/chengyinhub-admin/sql/migration_20260815_tandianri_h8_publisher_points_funds.sql" >/dev/null
"$RIG_HOME/venv/bin/python" "$RIG/chains.py" sale_close
RIG_SWITCHES="$MODE" bash "$HERE/install-channel.sh"
rm -f "$RIG_HOME/out/channel-ledger.jsonl"
"$RIG_HOME/venv/bin/python" "$HERE/money_chains.py" "$MODE" "$RIG_HOME/out/money-$MODE.json"
